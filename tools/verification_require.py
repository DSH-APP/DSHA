"""发布门禁的显式断言；Python -O 不得删掉检查。"""


def require(condition, message="VERIFICATION_FAILED"):
    if not condition:
        raise RuntimeError(str(message))
