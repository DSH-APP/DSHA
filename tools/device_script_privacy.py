"""Redact explicitly selected device and local tool paths from runner output."""


def redact(value, identifiers):
    if isinstance(value, dict):
        return {key: redact(item, identifiers) for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item, identifiers) for item in value]
    if not isinstance(value, str):
        return value
    for identifier in identifiers:
        if identifier:
            value = value.replace(str(identifier), "[local selection]")
    return value
