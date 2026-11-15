//! R1 端口骨架：`digest(fd_range, algorithm)`（方案 §4.3）。
//!
//! 首期只允许对 Kotlin 已核验并移交的 `fd + offset + length` 做只读摘要；
//! Rust 不 `open`/`stat`/`close`、不接收路径字符串、不决定 fd 是否可信。
//! R0 只冻结常量与模块边界，不导出可调用实现。
#![forbid(unsafe_code)]

/// `algorithm` 取值：SHA-256（与 Java `MessageDigest` 名称对应）。
pub const ALGORITHM_SHA256: i32 = 1;
/// `algorithm` 取值：SHA-1。
pub const ALGORITHM_SHA1: i32 = 2;

/// 单次调用允许的最大长度（32 MiB）；大文件由 Kotlin 分段循环。
pub const MAX_LENGTH: u64 = 33_554_432;

/// 内部 `read_at` 流式缓冲（256 KiB）；不按输入长度做无界分配。
pub const CHUNK: usize = 262_144;

/// 摘要输出上限：SHA-256 为 32 B，SHA-1 为 20 B；调用方必须提供至少 32 B。
pub const MAX_DIGEST_BYTES: usize = 32;
