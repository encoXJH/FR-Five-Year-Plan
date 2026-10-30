import re
from decimal import Decimal, InvalidOperation
from typing import Any

from app.errors import AppError
from app.models import DictIndicatorUnit


NUMERIC_KINDS = {"DECIMAL", "PERCENT", "PERCENT_YEAR", "COMPOUND_DECIMAL"}
NUMERIC_PATTERN = re.compile(r"^[≥<=+\-\s]*([+-]?(?:\d+(?:\.\d+)?|\.\d+))(?:\s*%)?$")


def raw_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    text = str(value).strip()
    return text if text else None


def parse_decimal_text(raw: Any, percent: bool = False) -> Decimal | None:
    text = raw_text(raw)
    if text is None:
        return None
    normalized = text.replace("，", ",").replace(",", "").replace("％", "%")
    match = NUMERIC_PATTERN.fullmatch(normalized)
    if not match:
        return None
    if percent and "%" not in normalized and not NUMERIC_PATTERN.fullmatch(normalized):
        return None
    try:
        return Decimal(match.group(1))
    except InvalidOperation:
        return None


def parse_target(raw: Any, unit: DictIndicatorUnit) -> dict[str, Any]:
    text = raw_text(raw)
    result: dict[str, Any] = {"value": None, "bool": None, "text": None, "raw": text, "parse_issue": None}
    if text is None:
        return result
    kind = unit.value_kind
    if kind == "BOOLEAN":
        if text == "是":
            result["bool"] = True
        elif text == "否":
            result["bool"] = False
        else:
            result["text"] = text
            result["parse_issue"] = "是/否目标仅接受是、否或空白"
        return result
    if kind == "RATIO":
        result["text"] = text
        if not re.search(r"\d+\s*[:：]\s*\d+", text):
            result["parse_issue"] = "比值须保留比例表达式并配置分子、分母"
        return result
    if kind == "NONE":
        result["text"] = text
        return result
    value = parse_decimal_text(text, percent=kind in {"PERCENT", "PERCENT_YEAR"})
    if value is not None:
        result["value"] = value
    else:
        result["text"] = text
        if text not in {"—", "无"}:
            result["parse_issue"] = "目标不是可解析数值，已保留原文，需人工确认"
    return result


def assign_target_fields(obj: Any, prefix: str, parsed: dict[str, Any]) -> None:
    setattr(obj, f"{prefix}_value", parsed.get("value"))
    setattr(obj, f"{prefix}_bool", parsed.get("bool"))
    setattr(obj, f"{prefix}_text", parsed.get("text"))
    if hasattr(obj, f"{prefix}_raw"):
        setattr(obj, f"{prefix}_raw", parsed.get("raw"))


def value_triplet(obj: Any, prefix: str) -> dict[str, Any]:
    return {
        "value": getattr(obj, f"{prefix}_value", None),
        "bool": getattr(obj, f"{prefix}_bool", None),
        "text": getattr(obj, f"{prefix}_text", None),
    }


def validate_single_value_kind(unit: DictIndicatorUnit, value: Decimal | None, bool_value: bool | None, text: str | None, label: str) -> None:
    supplied = int(value is not None) + int(bool_value is not None) + int(text is not None)
    if supplied > 1:
        raise AppError(422, "MULTIPLE_VALUE_KINDS", f"{label}不能同时填写数值、布尔和文本字段")
    if unit.value_kind == "BOOLEAN" and value is not None:
        raise AppError(422, "BOOLEAN_VALUE_REQUIRED", f"{label}必须使用布尔字段")
    if unit.value_kind in {"DECIMAL", "PERCENT", "PERCENT_YEAR"} and bool_value is not None:
        raise AppError(422, "DECIMAL_VALUE_REQUIRED", f"{label}必须使用数值字段")
    if unit.value_kind == "NONE" and (value is not None or bool_value is not None):
        raise AppError(422, "NOT_APPLICABLE_VALUE", f"{label}不适用数值或布尔值")
    if value is not None and value < 0:
        raise AppError(422, "NEGATIVE_VALUE", f"{label}不能为负数")


def compare_values(actual: Decimal, target: Decimal, direction: str | None) -> bool:
    direction = direction or "GE"
    if direction == "GE":
        return actual >= target
    if direction == "LE":
        return actual <= target
    if direction == "EQ":
        return actual == target
    raise AppError(422, "INVALID_TARGET_DIRECTION", "目标方向必须为 GE、LE 或 EQ")
