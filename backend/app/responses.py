import json
from typing import Any

from fastapi.responses import JSONResponse

from app.utils import json_value


class DecimalJSONResponse(JSONResponse):
    def render(self, content: Any) -> bytes:
        return json.dumps(
            json_value(content),
            ensure_ascii=False,
            allow_nan=False,
            indent=None,
            separators=(",", ":"),
        ).encode("utf-8")
