//! R2 端口骨架：规范化 `PlanView` 只读校验（方案 §4.6）。
//!
//! Rust 不解析 JSON、不执行计划、不产生副作用、不接收 argv/路径/命令；
//! `nowMs` 只是输入，Rust 不读系统时钟。R0 只冻结 v1 布局常量与模块边界。
#![forbid(unsafe_code)]

/// 头部 magic（小端字节 `44 53 48 50` = "DSHP"）。
pub const MAGIC: [u8; 4] = *b"DSHP";

/// `viewVersion`（首期只接受 1）。
pub const VIEW_VERSION: u16 = 1;

/// 单次视图最大长度（2 MiB = `BackupLimits.MANIFEST`）。
pub const MAX_VIEW_BYTES: usize = 2 * 1024 * 1024;

/// 字段数上限。
pub const MAX_FIELDS: u32 = 64;

/// 每字段长度上限（字符串字段另受 UTF-8 非空、无 NUL 约束）。
pub const MAX_FIELD_BYTES: u32 = 4_096;

/// 返回原因集合上限（按字段序/码序）。
pub const MAX_REASONS: usize = 8;
