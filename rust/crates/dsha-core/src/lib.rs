//! DSHA Rust 旁路 crate（R0 工具链骨架）。
//!
//! 首期只允许三类封闭纯函数（ADR-0004 决定四）：单成员 gzip 编解码、只读摘要、
//! 计划只读校验。本 crate **不接收路径字符串、不打开文件、不创建进程/线程、
//! 不安装信号处理器、不写 stdout/stderr/logcat、不联网**。
//!
//! R0 只含 ABI 探针与模块骨架：`abiVersion` 返回 1，其余 §4.2 入口返回
//! `NOT_IMPLEMENTED`，不接入任何生产路径。ABI 版本、符号名、错误码与上限在
//! R0 冻结（R1–R3 不得改；变化 = 新 ABI 版本 + 新评审）。

#![deny(unsafe_code)]
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(unused_must_use)]

pub mod abi;
pub mod digest;
pub mod gzip;
pub mod plan;

// 唯一允许 `unsafe` 的边界（JNI 胶水；R1 起另含只读 fd 读取）。
// 当前 R0 尚未出现 `unsafe` 代码，但模块豁免按 ADR-0004 决定四预先固定。
#[allow(unsafe_code)]
mod jni;

#[cfg(test)]
mod tests {
    use crate::abi::{
        ABI_VERSION, ERR_GZIP_DEFLATE, ERR_GZIP_HEADER, ERR_GZIP_TRAILER, ERR_INTERNAL,
        ERR_INVALID_ARGUMENT, ERR_JNI_PENDING, ERR_NOT_IMPLEMENTED, ERR_OK, ERR_OUTPUT_BOUND,
        ERR_PANIC, ERR_PLAN_EXPIRED, ERR_PLAN_FIELD_RANGE, ERR_PLAN_STRUCTURE, ERR_SESSION_LIMIT,
        ERR_SHORT_READ, ERR_STALE_HANDLE, ERR_UNSUPPORTED_ALGORITHM,
    };

    #[test]
    fn abi_version_is_frozen_at_one() {
        assert_eq!(ABI_VERSION, 1);
    }

    #[test]
    fn error_codes_are_unique_and_signed_as_documented() {
        let codes = [
            ERR_OK,
            ERR_INVALID_ARGUMENT,
            ERR_OUTPUT_BOUND,
            ERR_STALE_HANDLE,
            ERR_SESSION_LIMIT,
            ERR_INTERNAL,
            ERR_PANIC,
            ERR_JNI_PENDING,
            ERR_SHORT_READ,
            ERR_UNSUPPORTED_ALGORITHM,
            ERR_GZIP_HEADER,
            ERR_GZIP_DEFLATE,
            ERR_GZIP_TRAILER,
            ERR_PLAN_STRUCTURE,
            ERR_PLAN_FIELD_RANGE,
            ERR_PLAN_EXPIRED,
            ERR_NOT_IMPLEMENTED,
        ];
        assert_eq!(codes[0], 0);
        for pair in codes.windows(2) {
            assert!(pair[1] < 0, "错误码必须为负数: {}", pair[1]);
            assert_ne!(pair[0], pair[1], "错误码不得重复");
        }
        assert_eq!(ERR_PANIC, -7);
        assert_eq!(ERR_NOT_IMPLEMENTED, -26);
    }

    #[test]
    fn abi_limits_match_the_frozen_contract() {
        assert_eq!(crate::digest::MAX_LENGTH, 33_554_432);
        assert_eq!(crate::digest::CHUNK, 262_144);
        assert_eq!(crate::gzip::MAX_INPUT_CHUNK, 262_144);
        assert_eq!(crate::gzip::MAX_METADATA, 8_192);
        assert_eq!(crate::plan::MAX_VIEW_BYTES, 2 * 1024 * 1024);
        assert_eq!(&crate::plan::MAGIC, b"DSHP");
    }

    #[test]
    fn guard_maps_unwind_panic_to_error_code() {
        // 仅测试代码允许构造 panic；生产入口没有 panic!/unwrap/expect 路径。
        assert_eq!(
            crate::jni::guard(|| panic!("测试注入：catch_unwind 必须映射为 PANIC")),
            i64::from(ERR_PANIC)
        );
        assert_eq!(crate::jni::guard(|| 7), 7);
    }

    #[test]
    fn r0_exports_probe_and_not_implemented_stubs() {
        let env = std::ptr::null_mut();
        let class = std::ptr::null_mut();
        let abi = crate::jni::Java_com_deepseekharness_app_util_RustNative_abiVersion(env, class);
        assert_eq!(abi, ABI_VERSION);
        // R0 的其余入口只是冻结符号名的占位：必须返回 NOT_IMPLEMENTED，不做任何副作用。
        assert_eq!(
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackCreate(
                env, class, 6
            ),
            i64::from(ERR_NOT_IMPLEMENTED)
        );
        // 全部导出符号都在 R0 冻结；这里逐一取函数地址，防止改名后只改 Rust 忘记 Java。
        let _ = (
            crate::jni::Java_com_deepseekharness_app_util_RustNative_digest,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackUpdate,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackFinish,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archivePackAbort,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackCreate,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackWrite,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackRead,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_archiveUnpackAbort,
            crate::jni::Java_com_deepseekharness_app_util_RustNative_verifyPlan,
        );
    }
}
