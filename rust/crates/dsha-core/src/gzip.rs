//! R3 端口骨架：单成员 RFC 1952 gzip 编解码（方案 §4.4、§4.5）。
//!
//! 只做有界字节流，不写 tar/容器格式、不接收路径、不做文件 I/O、不做多成员。
//! R0 只冻结常量与模块边界；`archive_pack`/`archive_unpack` 入口返回
//! `NOT_IMPLEMENTED`。
#![forbid(unsafe_code)]

/// 单次 `update`/`write` 输入上限（256 KiB，沿用现有 SAF 读块）。
pub const MAX_INPUT_CHUNK: usize = 262_144;

/// 单次 `read` 输出上限（256 KiB）。
pub const MAX_OUTPUT_CHUNK: usize = 262_144;

/// FNAME/FCOMMENT 最大长度（含终止 0）；与 `StrictGzipInputStream` 一致。
pub const MAX_METADATA: usize = 8_192;

/// `pack` 会话压缩级别范围（0 = store，默认 6）。
pub const MIN_LEVEL: i32 = 0;
pub const MAX_LEVEL: i32 = 9;
pub const DEFAULT_LEVEL: i32 = 6;
