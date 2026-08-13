/**
 * Typed wrapper around the GlyphTeX TeX engine WebAssembly module.
 *
 * The boundary is deliberately split: options and results cross as JSON (small,
 * once per compile, and typed by declarations generated from the Rust
 * definitions), while file bytes move through linear memory directly.
 */
import { createImports, ExitStatus } from './imports.js';
/**
 * ABI version this wrapper is written against.
 *
 * 2 — `clear_files`, `clear_outputs` and `file_count` return a status code
 * instead of nothing, so they can report a poisoned session (see
 * {@link EnginePoisonedError}). Under 1 they could only fail silently or trap.
 */
export const SUPPORTED_ABI_VERSION = 2;
/** Raised when the module rejects an argument outright. */
export class EngineError extends Error {
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
 * Hosts that keep one engine for a whole session — the web worker does — should
 * catch this specifically and rebuild rather than reporting a compile failure,
 * because every later compile would fail too.
 */
export class EnginePoisonedError extends EngineError {
    constructor() {
        super('the TeX engine is no longer usable: an earlier compile crashed inside the ' +
            'module. Load a new engine instance to continue.', POISONED);
        this.name = 'EnginePoisonedError';
    }
}
export class TexEngine {
    #exports;
    #encoder = new TextEncoder();
    #decoder = new TextDecoder();
    /** Job name of the most recent compile; the default for pdf() and log(). */
    #jobname = 'main';
    constructor(exports) {
        this.#exports = exports;
    }
    /**
     * Instantiate the engine.
     *
     * Prefer passing a `Response` (or a promise of one) in the browser so the
     * module compiles while it downloads — that streaming path is also the only
     * one V8 will populate its compiled-code cache from.
     */
    static async load(source, io = {}) {
        const { imports, bind } = createImports(io);
        // Bytes are compiled to a Module first rather than handed straight to
        // `WebAssembly.instantiate`. That call is overloaded — bytes resolve to
        // `{ module, instance }`, a Module resolves to an Instance — and since
        // `WebAssembly.Module` is declared as an empty interface, a BufferSource
        // matches it structurally and TypeScript binds the wrong signature. The
        // separate `compile` step is unambiguous and behaves identically.
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
            exports._start?.();
        }
        catch (e) {
            if (!(e instanceof ExitStatus))
                throw e;
        }
        // Check the export exists before calling it. Loading a module built from
        // different source — nl5887's original, say, which exports tectonic_* —
        // otherwise fails as "undefined is not a function" from deep inside
        // load(), which says nothing about the actual mismatch.
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
        return this.#check(this.#exports.glyphx_file_count(), 'read the file count');
    }
    /** Add or replace a file. Accepts a string (encoded UTF-8) or raw bytes. */
    addFile(name, data) {
        const bytes = typeof data === 'string' ? this.#encoder.encode(data) : data;
        const namePtr = this.#write(this.#encoder.encode(name));
        const dataPtr = this.#write(bytes);
        try {
            const rc = this.#exports.glyphx_add_file(namePtr.ptr, namePtr.len, dataPtr.ptr, dataPtr.len);
            if (rc === POISONED)
                throw new EnginePoisonedError();
            if (rc !== 0)
                throw this.#error(`could not add "${name}"`, rc);
        }
        finally {
            this.#release(namePtr);
            this.#release(dataPtr);
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
        const n = this.#write(this.#encoder.encode(name));
        try {
            const rc = this.#exports.glyphx_remove_file(n.ptr, n.len);
            if (rc === POISONED)
                throw new EnginePoisonedError();
            if (rc < 0)
                throw this.#error(`could not remove "${name}"`, rc);
            return rc === 1;
        }
        finally {
            this.#release(n);
        }
    }
    /** Drop every input file. */
    clearFiles() {
        this.#check(this.#exports.glyphx_clear_files(), 'clear the input files');
    }
    /** Discard the previous compile's auxiliary files, forcing a cold build. */
    clearOutputs() {
        this.#check(this.#exports.glyphx_clear_outputs(), 'clear the previous outputs');
    }
    /**
     * Compile a document.
     *
     * Resolves for any completed run — including one that failed to produce a
     * PDF. Check `result.status`: `failed` is the only value meaning no output
     * exists, because TeX recovers from most errors and still typesets.
     */
    compile(request = {}) {
        const payload = this.#encoder.encode(JSON.stringify(request));
        const opts = this.#write(payload);
        try {
            const rc = this.#exports.glyphx_compile(opts.ptr, opts.len);
            // Checked before reading the result: a poisoned session has no result
            // to read, and the stale bytes from the previous compile would be
            // reported as if they described this one.
            if (rc === POISONED)
                throw new EnginePoisonedError();
            const result = this.#readResult();
            // A negative code means the request itself was unusable; the result
            // still carries the explanation, so prefer its message.
            if (rc < 0 && result.status !== 'failed') {
                throw this.#error('compile request rejected', rc);
            }
            // Remember the job name the engine actually used, so pdf() and log()
            // work without the caller restating it. Taken from the produced file
            // names rather than recomputed from the request, because the
            // derivation rule lives in Rust and must not be duplicated here.
            const artifact = result.outputs.find((o) => o.kind === 'pdf' || o.kind === 'log');
            if (artifact) {
                this.#jobname = artifact.name.replace(/\.[^.]+$/, '');
            }
            return result;
        }
        finally {
            this.#release(opts);
        }
    }
    /** Fetch the bytes of a file produced by the last compile. */
    output(name) {
        const n = this.#write(this.#encoder.encode(name));
        try {
            const size = this.#exports.glyphx_output_len(n.ptr, n.len);
            if (size < 0)
                return undefined;
            if (size === 0)
                return new Uint8Array(0);
            const buf = this.#exports.glyphx_alloc(size);
            if (buf === 0)
                throw new EngineError(`could not allocate ${size} bytes for "${name}"`);
            try {
                const written = this.#exports.glyphx_output_copy(buf, size);
                if (written < 0)
                    throw new EngineError(`could not read "${name}"`);
                // Copy out of linear memory before returning: the buffer is
                // about to be freed, and memory growth can detach the view.
                return new Uint8Array(this.#exports.memory.buffer, buf, written).slice();
            }
            finally {
                this.#exports.glyphx_dealloc(buf, size);
            }
        }
        finally {
            this.#release(n);
        }
    }
    /**
     * Convenience: the compiled PDF, if the last compile produced one.
     *
     * Defaults to the job name of the last compile, so it needs no argument in
     * the common case.
     */
    pdf(jobname = this.#jobname) {
        return this.output(`${jobname}.pdf`);
    }
    /** Convenience: the TeX log from the last compile, decoded as text. */
    log(jobname = this.#jobname) {
        const bytes = this.output(`${jobname}.log`);
        return bytes && this.#decoder.decode(bytes);
    }
    #readResult() {
        const ptr = this.#exports.glyphx_result_ptr();
        const len = this.#exports.glyphx_result_len();
        if (len === 0) {
            throw new EngineError('engine returned an empty result');
        }
        // Build the view after the call — a growing memory detaches older ones.
        const json = this.#decoder.decode(new Uint8Array(this.#exports.memory.buffer, ptr, len));
        return JSON.parse(json);
    }
    #write(bytes) {
        if (bytes.length === 0)
            return { ptr: 0, len: 0 };
        const ptr = this.#exports.glyphx_alloc(bytes.length);
        if (ptr === 0) {
            throw new EngineError(`could not allocate ${bytes.length} bytes`);
        }
        new Uint8Array(this.#exports.memory.buffer, ptr, bytes.length).set(bytes);
        return { ptr, len: bytes.length };
    }
    #release({ ptr, len }) {
        if (ptr !== 0)
            this.#exports.glyphx_dealloc(ptr, len);
    }
    /**
     * Turn a status code into a value or an error.
     *
     * A poisoned session gets its own type: it is not a failure of *this* call
     * and retrying is pointless, so a caller must be able to tell it apart from
     * an ordinary rejected argument.
     */
    #check(code, context) {
        if (code === POISONED)
            throw new EnginePoisonedError();
        if (code < 0)
            throw this.#error(`could not ${context}`, code);
        return code;
    }
    #error(context, code) {
        const detail = ERROR_CODES[code] ?? `error ${code}`;
        return new EngineError(`${context}: ${detail}`, code);
    }
}
function isResponseLike(source) {
    return (source instanceof Response ||
        (typeof source === 'object' && source !== null && 'then' in source));
}
//# sourceMappingURL=engine.js.map