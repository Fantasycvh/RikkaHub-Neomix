//! Tectonic Android POC 库（方案 B 第 1 步）
//!
//! 宿主形态：tex-poc CLI（main.rs）；Android 形态：cdylib 暴露 C ABI
//! `poc_compile` + `poc_free`，供后续 JNI 桥接（第 2 步）调用。

use std::collections::HashMap;
use std::io::Cursor;

use tectonic::driver::{OutputFormat, ProcessingSessionBuilder};
use tectonic::io::{InputHandle, InputOrigin, IoProvider, OpenResult};
use tectonic::status::{NoopStatusBackend, StatusBackend};
use tectonic::io::digest::DigestData;
use tectonic_bundles::Bundle;

/// 极简 UStar tar 解包：512 字节头 + 数据块按 512 对齐
pub fn load_tar_gz(bytes: &[u8]) -> Result<HashMap<String, Vec<u8>>, Box<dyn std::error::Error>> {
    let tar = flate2::read::GzDecoder::new(Cursor::new(bytes));
    let mut archive = tar::Archive::new(tar);
    let mut files = HashMap::new();
    for entry in archive.entries()? {
        let mut entry = entry?;
        if !entry.header().entry_type().is_file() {
            continue;
        }
        let name = entry.path()?.to_string_lossy().to_string();
        let mut data = Vec::new();
        std::io::Read::read_to_end(&mut entry, &mut data)?;
        files.insert(name, data);
    }
    Ok(files)
}

struct MemBundle {
    files: HashMap<String, Vec<u8>>,
}

impl IoProvider for MemBundle {
    fn input_open_name(
        &mut self,
        name: &str,
        _status: &mut dyn StatusBackend,
    ) -> OpenResult<InputHandle> {
        if std::env::var("TEX_POC_DEBUG").as_deref() == Ok("1") {
            eprintln!("[poc] input_open_name: {name}");
        }
        match self.files.get(name) {
            Some(data) => OpenResult::Ok(InputHandle::new(
                name,
                Cursor::new(data.clone()),
                InputOrigin::Other,
            )),
            None => OpenResult::NotAvailable,
        }
    }
}

impl Bundle for MemBundle {
    fn get_digest(&mut self) -> std::result::Result<DigestData, tectonic::Error> {
        // POC：常量摘要即可（一次性编译，无重跑决策依赖）
        Ok(DigestData::zeros())
    }
    fn all_files(&self) -> Vec<String> {
        self.files.keys().cloned().collect()
    }
}

/// 完整编译：bundle 文件 + 字体字节 + tex 源码 → PDF 字节。
/// 不信任历史 bundle 里的 latex.fmt（旧引擎生成，过不了版本校验）：
/// 移除后由本版本 tectonic 从 latex.ltx 重建。
/// cache_dir：format 缓存目录（首次重建 6s 后持久化，后续 ~0.5s），None 则不缓存。
pub fn tex_compile(
    mut files: HashMap<String, Vec<u8>>,
    font: Vec<u8>,
    tex_source: &[u8],
    cache_dir: Option<&std::path::Path>,
    output_format: OutputFormat,
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    files.remove("latex.fmt");
    files.insert("droidsans.ttf".to_string(), font);
    // tectonic 驱动建 format 时会打开 "tectonic-format-<name>.tex"（官方 bundle 自带），
    // 我们的精简 bundle 没有：合成一个，内容即 latex.ltx 入口
    files.insert(
        "tectonic-format-latex.tex".to_string(),
        b"\\input latex.ltx\n".to_vec(),
    );

    let mut sb = ProcessingSessionBuilder::default();
    sb.bundle(Box::new(MemBundle { files }));
    sb.format_name("latex");
    sb.tex_input_name("main.tex");
    sb.primary_input_buffer(tex_source);
    sb.output_format(output_format);
    sb.do_not_write_output_files();
    sb.keep_intermediates(false);
    // TeX 非交互恢复模式对含错误文档仍可能产出 PDF：保留 main.log 用于错误判定
    sb.keep_logs(true);
    if let Some(dir) = cache_dir {
        sb.format_cache_path(dir);
    }

    // 调试开关：TEX_POC_DEBUG=1 时打印引擎 stdout 并保留 log，用于排查编译错误
    if std::env::var("TEX_POC_DEBUG").as_deref() == Ok("1") {
        sb.print_stdout(true);
        sb.keep_logs(true);
    }

    let mut session = sb.create(&mut NoopStatusBackend {})?;
    let run_result = session.run(&mut NoopStatusBackend {});
    let outputs = session.into_file_data();

    // main.log 中以 "! " 开头的行是 TeX 错误；恢复模式下即便产出 PDF 也应视为编译失败
    // 提取首条错误行进错误信息，便于 JNI lastErrorText() 直接暴露 TeX 诊断。
    // 注意：XeTeX 遇到致命错误（如 Illegal parameter number）时 session.run 本身
    // 就返回 "unrecoverable error"——main.log 里往往仍有带 "! " 的详细行，优先取它。
    if let Some(log) = outputs.get("main.log") {
        let text = String::from_utf8_lossy(&log.data);
        let first_err = text.lines().find(|l| l.starts_with("! "));
        if let Some(err_line) = first_err {
            let detail = err_line.trim_start_matches("! ").trim().to_string();
            return Err(format!("TeX error: {detail}").into());
        }
    }
    if let Err(e) = run_result {
        return Err(e.into());
    }

    let out_name = match output_format {
        OutputFormat::Pdf => "main.pdf",
        OutputFormat::Xdv => "main.xdv",
        _ => "main.pdf",
    };
    match outputs.get(out_name) {
        Some(info) if !info.data.is_empty() => Ok(info.data.clone()),
        Some(_) => Err(format!("{out_name} is empty").into()),
        None => Err(format!("no {out_name} in output").into()),
    }
}

/// C ABI 输出结构：指针 + 长度，调用方用 poc_free 释放
#[repr(C)]
pub struct PocBuffer {
    pub ptr: *mut u8,
    pub len: usize,
}

unsafe impl Send for PocBuffer {}

/// 一次性编译（POC 语义：每次调用全量载入 bundle）。
/// 返回 PocBuffer；失败时 ptr 为 null，错误详情见 last_error。
///
/// # Safety
/// bundle/font/tex 指针必须有效且长度准确；out 非空。
#[no_mangle]
pub unsafe extern "C" fn poc_compile(
    bundle: *const u8,
    bundle_len: usize,
    font: *const u8,
    font_len: usize,
    tex: *const u8,
    tex_len: usize,
    out: *mut PocBuffer,
) -> i32 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bundle = std::slice::from_raw_parts(bundle, bundle_len);
        let font = std::slice::from_raw_parts(font, font_len).to_vec();
        let tex = std::slice::from_raw_parts(tex, tex_len);
        let files = load_tar_gz(bundle)?;
        tex_compile(files, font, &tex, None, tectonic::driver::OutputFormat::Pdf)
    }));
    match result {
        Ok(Ok(pdf)) => {
            let mut pdf = pdf;
            let ptr = pdf.as_mut_ptr();
            let len = pdf.len();
            std::mem::forget(pdf);
            (*out) = PocBuffer { ptr, len };
            0
        }
        Ok(Err(e)) => {
            eprintln!("[poc] compile error: {e}");
            -1
        }
        Err(_) => {
            eprintln!("[poc] compile panicked");
            -2
        }
    }
}

/// # Safety
/// ptr/len 必须来自 poc_compile 的成功输出。
#[no_mangle]
pub unsafe extern "C" fn poc_free(ptr: *mut u8, len: usize) {
    if !ptr.is_null() && len > 0 {
        drop(Vec::from_raw_parts(ptr, len, len));
    }
}

// ---- JNI（Android app 集成，第 2 步）----

use std::sync::Mutex;
use std::sync::OnceLock;

fn last_error() -> &'static Mutex<Option<String>> {
    static LAST_ERROR: OnceLock<Mutex<Option<String>>> = OnceLock::new();
    LAST_ERROR.get_or_init(|| Mutex::new(None))
}

fn set_last_error(msg: String) {
    if let Ok(mut slot) = last_error().lock() {
        *slot = Some(msg);
    }
}

/// JNI 入口：app.androidrik.rikkahub.tikz.TectonicNative
/// compile([B bundle, [B font, [B tex, String cacheDir) -> [B pdf | null
///
/// # Safety
/// 由 JNI 调用约定保证指针有效性。
#[no_mangle]
pub unsafe extern "system" fn Java_app_androidrik_rikkahub_tikz_TectonicNative_compile(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    bundle: jni::objects::JByteArray,
    font: jni::objects::JByteArray,
    tex: jni::objects::JByteArray,
    cache_dir: jni::objects::JString,
    format: jni::objects::JString,
) -> jni::sys::jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bundle: Vec<u8> = env.convert_byte_array(&bundle)?;
        let font: Vec<u8> = env.convert_byte_array(&font)?;
        let tex: Vec<u8> = env.convert_byte_array(&tex)?;
        let cache: String = env.get_string(&cache_dir)?.into();
        let format: String = env.get_string(&format)?.into();
        let out_format = match format.as_str() {
            "xdv" => tectonic::driver::OutputFormat::Xdv,
            _ => tectonic::driver::OutputFormat::Pdf,
        };
        let files = load_tar_gz(&bundle)?;
        tex_compile(files, font, &tex, Some(std::path::Path::new(&cache)), out_format)
    }));
    match result {
        Ok(Ok(pdf)) => match env.byte_array_from_slice(&pdf) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                set_last_error(format!("jni byte_array_from_slice: {e}"));
                env.exception_clear();
                std::ptr::null_mut()
            }
        },
        Ok(Err(e)) => {
            set_last_error(format!("{e}"));
            std::ptr::null_mut()
        }
        Err(_) => {
            set_last_error("compile panicked".to_string());
            std::ptr::null_mut()
        }
    }
}

/// 取最近一次 JNI 编译失败的错误信息（成功调用后不清空）。
///
/// # Safety
/// 由 JNI 调用约定保证。
#[no_mangle]
pub unsafe extern "system" fn Java_app_androidrik_rikkahub_tikz_TectonicNative_lastError(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    let msg = last_error()
        .lock()
        .ok()
        .and_then(|slot| slot.clone())
        .unwrap_or_else(|| "unknown error".to_string());
    match env.new_string(msg) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
