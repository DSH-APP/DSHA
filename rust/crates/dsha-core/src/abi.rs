//! ABI 版本、错误码与首期上限（方案 §4.2；R0 冻结，R1–R3 不得改）。
//!
//! 错误码是 `i32`：`0` = 成功，负数 = 错误；纯函数不返回 Java 异常。
#![forbid(unsafe_code)]

/// ABI 版本；`abiVersion()` 必须返回该值，Java 侧不匹配即拒绝加载。
pub const ABI_VERSION: i32 = 1;

pub const ERR_OK: i32 = 0;
pub const ERR_INVALID_ARGUMENT: i32 = -1;
pub const ERR_INPUT_BOUND: i32 = -2;
pub const ERR_OUTPUT_BOUND: i32 = -3;
pub const ERR_STALE_HANDLE: i32 = -4;
pub const ERR_SESSION_LIMIT: i32 = -5;
pub const ERR_INTERNAL: i32 = -6;
pub const ERR_PANIC: i32 = -7;
pub const ERR_JNI_PENDING: i32 = -8;
pub const ERR_SHORT_READ: i32 = -9;
pub const ERR_UNSUPPORTED_ALGORITHM: i32 = -10;
pub const ERR_GZIP_HEADER: i32 = -11;
pub const ERR_GZIP_HEADER_CRC: i32 = -12;
pub const ERR_GZIP_METADATA_LIMIT: i32 = -13;
pub const ERR_GZIP_TRUNCATED: i32 = -14;
pub const ERR_GZIP_DEFLATE: i32 = -15;
pub const ERR_GZIP_DICTIONARY: i32 = -16;
pub const ERR_GZIP_STALLED: i32 = -17;
pub const ERR_GZIP_TRAILER: i32 = -18;
pub const ERR_GZIP_TRAILING_DATA: i32 = -19;
pub const ERR_PLAN_STRUCTURE: i32 = -20;
pub const ERR_PLAN_FIELD_RANGE: i32 = -21;
pub const ERR_PLAN_POLICY_VERSION: i32 = -22;
pub const ERR_PLAN_EXPIRED: i32 = -23;
pub const ERR_PLAN_LIMIT: i32 = -24;
pub const ERR_PLAN_UNKNOWN_FIELD: i32 = -25;
pub const ERR_NOT_IMPLEMENTED: i32 = -26;

/// 首期并发会话上限（§4.2 建议 4；R0 冻结）。
pub const MAX_SESSIONS: usize = 4;

/// 安装一个**不输出任何内容**的 panic hook（G8：Rust 不写 stdout/stderr）。
///
/// 默认 hook 会在 `catch_unwind` 之前向 stderr 打印 panic 文本；release 里这既
/// 违反“Rust 不打日志”，也可能泄露路径。这里只在首次 FFI 调用时安装一次，
/// 只丢弃输出，不改变 panic 的展开语义（`panic = "unwind"`）。
pub fn silence_panic_output() {
    static INSTALL: std::sync::Once = std::sync::Once::new();
    INSTALL.call_once(|| {
        std::panic::set_hook(Box::new(|_| {}));
    });
}

#[cfg(test)]
mod tests {
    #[test]
    fn abi_version_is_frozen() {
        assert_eq!(super::ABI_VERSION, 1);
    }
}
