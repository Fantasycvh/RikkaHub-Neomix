/**
 * Typed wrapper around the GlyphTeX TeX engine WebAssembly module.
 *
 * The boundary is deliberately split: options and results cross as JSON (small,
 * once per compile, and typed by declarations generated from the Rust
 * definitions), while file bytes move through linear memory directly.
 *
 * NOTE: this file is a plain script (no ES module). Android WebView's
 * `<script type="module">` + `import` failed silently (MIME / module-support
 * issues), while a plain `<script>` is guaranteed to execute. `createImports`
 * and `ExitStatus` are provided globally by `imports.js` loaded first.
 */

/** ABI version this wrapper is written against.
 *
 * 2 — `clear_files`, `clear_outputs` and `file_count` return a status code
 * instead of nothing, so they can report a poisoned session (see
 * {@link EnginePoisonedError}). Under 1 they could only fail silently or trap.
 */
const SUPPORTED_ABI_VERSION = 2;
/** Raised when the module rejects an argument outright. */
class EngineError extends Error {
    code;
    constructor(message, code) {
        super(message);
        this.code = code;
        this.name = 'EngineError';
    }
}
const ERROR_CODES = {
    [-1]: 'invalid pointer or length',
    [-2]: 'argument was not valid UTF-8',
    [-3]: 'options JSON could not be parsed',
    [-4]: 'the engine is spent — a previous compile trapped and left it unusable'
};
/** Status code the module returns once its session can no longer be used. */
const POISONED = -4;
/**
 * Raised once the module is unusable, whatever is called next.
 *
 * A compile that traps (rather than reporting an error) tears down the wasm
 * stack without unwinding Rust, which leaves the engine's session permanently
 * borrowed. Nothing recovers that from the inside.
 *
 * The only fix is a new instance: discard this `TexEngine` and `load()` again.
 */
class EnginePoisonedError extends EngineError {
    constructor() {
        super('the TeX engine is no longer usable: an earlier compile crashed inside the ' +
            'module. Load a new engine instance to continue.', POISONED);
        this.name = 'EnginePoisonedError';
    }
}
class TexEngine {
    _exports;
    _encoder = new TextEncoder();
    _decoder = new TextDecoder();
    /** Job name of the most recent compile; the default for pdf() and log(). */
    _jobname = 'main';
    constructor(exports) {
        this._exports = exports;
    }
    /**
     * Instantiate the engine.
     */
    static async load(source, io = {}) {
        const { imports, bind } = createImports(io);
        let instance;
        if (isResponseLike(source)) {
            const result = await WebAssembly.instantiateStreaming(source, imports);
            instance = result.instance;
        }
        else {
            const module = source instanceof WebAssembly.Module ? source : await WebAssembly.compile(source);
            instance = await WebAssembly.instantiate(module, imports);
        }
        const exports = instance.exports;
        bind(exports);
        // libc initialisation. It calls proc_exit on completion, which surfaces
        // as ExitStatus; that is expected rather than a failure.
        try {
            if (typeof exports._start === 'function') {
                exports._start();
            }
        }
        catch (e) {
            if (!(e instanceof ExitStatus))
                throw e;
        }
        if (typeof exports.glyphx_abi_version !== 'function') {
            const found = Object.keys(instance.exports)
                .filter((n) => !n.startsWith('__') && n !== 'memory')
                .slice(0, 8);
            throw new EngineError('this module does not export the GlyphTeX engine ABI ' +
                `(glyphx_abi_version is missing; it exports ${found.join(', ')}). ` +
                'It was built from different source than this wrapper.');
        }
        const abi = exports.glyphx_abi_version();
        if (abi !== SUPPORTED_ABI_VERSION) {
            throw new EngineError(`engine ABI ${abi} is not supported by this wrapper (expected ${SUPPORTED_ABI_VERSION})`);
        }
        return new TexEngine(exports);
    }
    /** How many files are currently in the virtual filesystem. */
    get fileCount() {
        return this._check(this._exports.glyphx_file_count(), 'read the file count');
    }
    /** Add or replace a file. Accepts a string (encoded UTF-8) or raw bytes. */
    addFile(name, data) {
        const bytes = typeof data === 'string' ? this._encoder.encode(data) : data;
        const namePtr = this._write(this._encoder.encode(name));
        const dataPtr = this._write(bytes);
        try {
            const rc = this._exports.glyphx_add_file(namePtr.ptr, namePtr.len, dataPtr.ptr, dataPtr.len);
            if (rc === POISONED)
                throw new EnginePoisonedError();
            if (rc !== 0)
                throw this._error(`could not add "${name}"`, rc);
        }
        finally {
            this._release(namePtr);
            this._release(dataPtr);
        }
    }
    /** Add many files at once — typically a bundle. */
    addFiles(files) {
        const entries = Symbol.iterator in files ? files : Object.entries(files);
        for (const [name, data] of entries) {
            this.addFile(name, data);
        }
    }
    /** Remove a file. Returns whether it was present. */
    removeFile(name) {
        const n = this._write(this._encoder.encode(name));
        try {
            const rc = this._exports.glyphx_remove_file(n.ptr, n.len);
            if (rc === POISONED)
                throw new EnginePoisonedError();
            if (rc < 0)
                throw this._error(`could not remove "${name}"`, rc);
            return rc === 1;
        }
        finally {
            this._release(n);
        }
    }
    /** Drop every input file. */
    clearFiles() {
        this._check(this._exports.glyphx_clear_files(), 'clear the input files');
    }
    /** Discard the previous compile's auxiliary files, forcing a cold build. */
    clearOutputs() {
        this._check(this._exports.glyphx_clear_outputs(), 'clear the previous outputs');
    }
    /**
     * Compile a document.
     *
     * Resolves for any completed run — including one that failed to produce a
     * PDF. Check `result.status`: `failed` is the only value meaning no output
     * exists, because TeX recovers from most errors and still typesets.
     */
    compile(request = {}) {
        const payload = this._encoder.encode(JSON.stringify(request));
        const opts = this._write(payload);
        try {
            const rc = this._exports.glyphx_compile(opts.ptr, opts.len);
            if (rc === POISONED)
                throw new EnginePoisonedError();
            const result = this._readResult();
            if (rc < 0 && result.status !== 'failed') {
                throw this._error('compile request rejected', rc);
            }
            const artifact = result.outputs.find((o) => o.kind === 'pdf' || o.kind === 'log');
            if (artifact) {
                this._jobname = artifact.name.replace(/\.[^.]+$/, '');
            }
            return result;
        }
        finally {
            this._release(opts);
        }
    }
    /** Fetch the bytes of a file produced by the last compile. */
    output(name) {
        const n = this._write(this._encoder.encode(name));
        try {
            const size = this._exports.glyphx_output_len(n.ptr, n.len);
            if (size < 0)
                return undefined;
            if (size === 0)
                return new Uint8Array(0);
            const buf = this._exports.glyphx_alloc(size);
            if (buf === 0)
                throw new EngineError(`could not allocate ${size} bytes for "${name}"`);
            try {
                const written = this._exports.glyphx_output_copy(buf, size);
                if (written < 0)
                    throw new EngineError(`could not read "${name}"`);
                // Copy out of linear memory before returning: the buffer is
                // about to be freed, and memory growth can detach the view.
                return new Uint8Array(this._exports.memory.buffer, buf, written).slice();
            }
            finally {
                this._exports.glyphx_dealloc(buf, size);
            }
        }
        finally {
            this._release(n);
        }
    }
    /**
     * Convenience: the compiled PDF, if the last compile produced one.
     */
    pdf(jobname = this._jobname) {
        return this.output(`${jobname}.pdf`);
    }
    /** Convenience: the TeX log from the last compile, decoded as text. */
    log(jobname = this._jobname) {
        const bytes = this.output(`${jobname}.log`);
        return bytes && this._decoder.decode(bytes);
    }
    _readResult() {
        const ptr = this._exports.glyphx_result_ptr();
        const len = this._exports.glyphx_result_len();
        if (len === 0) {
            throw new EngineError('engine returned an empty result');
        }
        const json = this._decoder.decode(new Uint8Array(this._exports.memory.buffer, ptr, len));
        return JSON.parse(json);
    }
    _write(bytes) {
        if (bytes.length === 0)
            return { ptr: 0, len: 0 };
        const ptr = this._exports.glyphx_alloc(bytes.length);
        if (ptr === 0) {
            throw new EngineError(`could not allocate ${bytes.length} bytes`);
        }
        new Uint8Array(this._exports.memory.buffer, ptr, bytes.length).set(bytes);
        return { ptr, len: bytes.length };
    }
    _release({ ptr, len }) {
        if (ptr !== 0)
            this._exports.glyphx_dealloc(ptr, len);
    }
    _check(code, context) {
        if (code === POISONED)
            throw new EnginePoisonedError();
        if (code < 0)
            throw this._error(`could not ${context}`, code);
        return code;
    }
    _error(context, code) {
        const detail = ERROR_CODES[code] !== undefined ? ERROR_CODES[code] : `error ${code}`;
        return new EngineError(`${context}: ${detail}`, code);
    }
}
function isResponseLike(source) {
    return (source instanceof Response ||
        (typeof source === 'object' && source !== null && 'then' in source));
}
// 普通 script 全局暴露（Android WebView 下不用 ES module）
window.TexEngine = TexEngine;
//# sourceMappingURL=engine.js.map