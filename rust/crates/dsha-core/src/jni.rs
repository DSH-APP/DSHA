//! JNI 胶水：唯一允许 `unsafe` 的边界（ADR-0004 决定四 / 方案 §4.1、§4.7）。
//!
//! R0 只做两件事：
//! 1. 用 §4.2 的固定符号名导出全部首期入口，证明主机 JVM 能 `System.load` 并走通
//!    一次原生调用（`abiVersion`），其余入口返回 `NOT_IMPLEMENTED`；
//! 2. 固定“每个入口 `catch_unwind`、panic 映射错误码、绝不跨 FFI 展开”的形状。
//!
//! 本文件不接收路径字符串、不打开文件、不创建进程/线程、不安装信号处理器、
//! 不调用任何会抛 Java 异常的 JNI 函数、不写 stdout/stderr。R1 起才在这里加
//! `GetDirectBufferAddress` 等 JNI 调用，并在每次调用后 `ExceptionCheck`。

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::abi::{silence_panic_output, ERR_NOT_IMPLEMENTED, ERR_OK, ERR_PANIC};

// 只使用 JNI 类型的最小投影，不引入 jni-rs（方案 §4.7 注）。
type JniEnv = *mut c_void;
type JClass = *mut c_void;
type JObject = *mut c_void;

/// 所有入口的统一保护层：`catch_unwind` 捕获可展开 panic 并映射为 `ERR_PANIC`。
///
/// 只覆盖可展开 panic；abort / SIGSEGV / OOM 按 ADR-0004 失败域矩阵不可捕获，
/// 由有界输入与最小 `unsafe` 预防（不在本层伪造“已处理”）。
pub(crate) fn guard(body: impl FnOnce() -> i64) -> i64 {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(value) => value,
        Err(_) => i64::from(ERR_PANIC),
    }
}

/// FFI 入口专用：先安装静默 panic hook，再进入 `guard`（G8：不写 stderr）。
fn ffi_guard(body: impl FnOnce() -> i64) -> i64 {
    silence_panic_output();
    guard(body)
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_abiVersion(
    _env: JniEnv,
    _class: JClass,
) -> i32 {
    ffi_guard(|| i64::from(crate::abi::ABI_VERSION)) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_digest(
    _env: JniEnv,
    _class: JClass,
    _fd: i32,
    _offset: i64,
    _length: i64,
    _algorithm: i32,
    _out: JObject,
) -> i64 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED))
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackCreate(
    _env: JniEnv,
    _class: JClass,
    _level: i32,
) -> i64 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED))
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackUpdate(
    _env: JniEnv,
    _class: JClass,
    _session: i64,
    _input: JObject,
    _out: JObject,
) -> i64 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED))
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackFinish(
    _env: JniEnv,
    _class: JClass,
    _session: i64,
    _out: JObject,
) -> i64 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED))
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackAbort(
    _env: JniEnv,
    _class: JClass,
    _session: i64,
) -> i32 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED)) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackCreate(
    _env: JniEnv,
    _class: JClass,
) -> i64 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED))
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackWrite(
    _env: JniEnv,
    _class: JClass,
    _session: i64,
    _input: JObject,
) -> i64 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED))
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackRead(
    _env: JniEnv,
    _class: JClass,
    _session: i64,
    _out: JObject,
) -> i64 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED))
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackAbort(
    _env: JniEnv,
    _class: JClass,
    _session: i64,
) -> i32 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED)) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_verifyPlan(
    _env: JniEnv,
    _class: JClass,
    _view: JObject,
    _view_length: i32,
    _reasons: JObject,
    _max_reasons: i32,
) -> i32 {
    ffi_guard(|| i64::from(ERR_NOT_IMPLEMENTED)) as i32
}

// `ERR_OK` 目前只用于文档/冻结；显式引用避免 clippy 把它当成未使用模块项。
const _: i32 = ERR_OK;
