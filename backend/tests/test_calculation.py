from decimal import Decimal
from types import SimpleNamespace

from app.services.calc import evaluate_annual


def indicator(method, direction="GE", baseline=None):
    return SimpleNamespace(calc_method=method, target_direction=direction, baseline_value=baseline)


def target(value=None, bool_value=None, text=None):
    return {"value": value, "bool": bool_value, "text": text}


def recognized(value=None, bool_value=None, text=None, numerator=None, denominator=None):
    return {"value": value, "bool": bool_value, "text": text, "numerator": numerator, "denominator": denominator}


def test_seven_calculation_methods_and_zero_rules():
    for method in ["CUMULATIVE", "ANNUAL_QUOTA", "FINAL"]:
        result = evaluate_annual(indicator(method), target(Decimal("5")), recognized(Decimal("5")))
        assert result["achieved"] is True
    ratio = evaluate_annual(indicator("RATIO"), target(Decimal("40")), recognized(numerator=Decimal("50"), denominator=Decimal("100")))
    assert ratio["actual"] == Decimal("50") and ratio["achieved"] is True
    zero_denominator = evaluate_annual(indicator("RATIO"), target(Decimal("40")), recognized(numerator=Decimal("50"), denominator=Decimal("0")))
    assert zero_denominator["status"] == "UNCALCULABLE" and zero_denominator["achieved"] is None
    growth = evaluate_annual(indicator("GROWTH", baseline=Decimal("100")), target(Decimal("15")), recognized(Decimal("115")))
    assert growth["actual"] == Decimal("15") and growth["achieved"] is True
    zero_baseline = evaluate_annual(indicator("GROWTH", baseline=Decimal("0")), target(Decimal("15")), recognized(Decimal("115")))
    assert zero_baseline["status"] == "UNCALCULABLE"
    yes = evaluate_annual(indicator("BOOLEAN"), target(bool_value=True), recognized(bool_value=True))
    no = evaluate_annual(indicator("BOOLEAN"), target(bool_value=True), recognized(bool_value=False))
    assert yes["achieved"] is True and no["achieved"] is False
    external = evaluate_annual(indicator("EXTERNAL"), target(Decimal("5")), recognized(Decimal("5")))
    missing_external = evaluate_annual(indicator("EXTERNAL"), target(Decimal("5")), recognized())
    assert external["achieved"] is True and missing_external["status"] == "UNCALCULABLE"


def test_le_eq_and_empty_semantics():
    assert evaluate_annual(indicator("CUMULATIVE", "LE"), target(Decimal("5")), recognized(Decimal("6")))["achieved"] is False
    assert evaluate_annual(indicator("CUMULATIVE", "EQ"), target(Decimal("5")), recognized(Decimal("5")))["achieved"] is True
    empty = evaluate_annual(indicator("CUMULATIVE"), target(None), recognized(Decimal("0")))
    assert empty["status"] == "UNCALCULABLE" and empty["actual"] is None
