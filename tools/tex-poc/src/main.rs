//! CLI 薄封装：宿主验证入口（Android 侧走 lib.rs 的 C ABI）

use std::collections::HashMap;
use std::io::Cursor;

use tex_poc::{load_tar_gz, tex_compile};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 6 {
        eprintln!("usage: tex-poc <bundle.tar.gz> <font.ttf> <input.tex> <out> <pdf|xdv>");
        std::process::exit(2);
    }
    let (bundle_path, font_path, tex_path, out_path) = (&args[1], &args[2], &args[3], &args[4]);
    let format = match args[5].as_str() {
        "xdv" => tectonic::driver::OutputFormat::Xdv,
        _ => tectonic::driver::OutputFormat::Pdf,
    };

    let started = std::time::Instant::now();
    let files: HashMap<String, Vec<u8>> =
        load_tar_gz(&std::fs::read(bundle_path).expect("read bundle")).expect("parse bundle");
    eprintln!(
        "[poc] bundle loaded: {} files ({:.1?})",
        files.len(),
        started.elapsed()
    );

    let font = std::fs::read(font_path).expect("read font");
    let tex = std::fs::read(tex_path).expect("read tex");
    let data = tex_compile(files, font, &tex, None, format).expect("compile failed");
    std::fs::write(out_path, &data).expect("write output");
    eprintln!(
        "[poc] OK: {} bytes, total {:.1?}",
        data.len(),
        started.elapsed()
    );
}

// 防止 Cursor 未使用告警（load_tar_gz 已在 lib 中）
#[allow(unused)]
fn _unused(c: Cursor<Vec<u8>>) {}
