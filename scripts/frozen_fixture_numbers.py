"""Exact JSON numbers for structural frozen-fixture reference checkers.

Their integer fields are inline in the selected root schemas. Reached external
common-schema definitions contain no integers; this extension is not a global
replacement for validators selected by an external root's ``$schema``.
"""
from decimal import Decimal, InvalidOperation
import json

from jsonschema import Draft202012Validator, validators


def reject_non_json_number(_value):
    raise ValueError("Fixture inputs require finite JSON numbers")


def exact_decimal(token):
    try:
        value = Decimal(token)
    except InvalidOperation as error:
        raise ValueError("Fixture number exceeds decimal representation") from error
    if not value.is_finite():
        # An out-of-range exponent can produce NaN instead of raising when a
        # caller has disabled the Decimal InvalidOperation trap.
        raise ValueError("Fixture number exceeds decimal representation")
    return value


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=exact_decimal,
                      parse_constant=reject_non_json_number)


def is_exact_integer(checker, value):
    if isinstance(value, Decimal):
        # Decimal keeps exponents compact. Do not materialize an integer or
        # quantize through a precision-limited arithmetic context.
        return value.is_finite() and value == value.to_integral_value()
    return Draft202012Validator.TYPE_CHECKER.is_type(value, "integer")


FixtureValidator = validators.extend(
    Draft202012Validator,
    type_checker=Draft202012Validator.TYPE_CHECKER.redefine("integer", is_exact_integer),
)
