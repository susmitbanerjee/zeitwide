"""The API reference as Markdown, generated from the OpenAPI document (api/openapi.yaml)."""

from collections.abc import Mapping
from typing import cast

_METHODS = ("get", "post", "put", "patch", "delete")

type Json = Mapping[str, object]


def api_reference(openapi: Json) -> str:
    info = _map(openapi.get("info"))
    lines = [
        "# API reference",
        "",
        f"{_text(info.get('title'))} {_text(info.get('version'))}. Generated from the OpenAPI "
        "document; every path is under the API's base URL.",
    ]
    for path, operations in sorted(_map(openapi.get("paths")).items()):
        for method in _METHODS:
            operation = _map(_map(operations).get(method))
            if operation:
                lines += ["", *_operation(method, path, operation, openapi)]
    schemas = _map(_map(openapi.get("components")).get("schemas"))
    lines += ["", "## Schemas"]
    for name, schema in sorted(schemas.items()):
        lines += ["", *_schema(name, _map(schema))]
    return "\n".join(lines) + "\n"


def _operation(method: str, path: str, operation: Json, openapi: Json) -> list[str]:
    lines = [f"## {method.upper()} {path}", ""]
    summary = _text(operation.get("summary"))
    if summary:
        lines.append(summary)
    description = _text(operation.get("description")).strip()
    if description:
        lines += ["", " ".join(description.split())]
    parameters = [_resolve(_map(p), openapi) for p in _list(operation.get("parameters"))]
    if parameters:
        lines += ["", "| Parameter | In | Required | Description |", "| --- | --- | --- | --- |"]
        lines += [
            f"| `{_text(p.get('name'))}` | {_text(p.get('in'))} | "
            f"{'yes' if p.get('required') else 'no'} | {_cell(_text(p.get('description')))} |"
            for p in parameters
        ]
    body = _map(operation.get("requestBody"))
    if body:
        lines += ["", f"Request body: {_content_schema(body)}."]
    responses = _map(operation.get("responses"))
    if responses:
        lines += ["", "| Status | Response |", "| --- | --- |"]
        for status, response in sorted(responses.items()):
            resolved = _resolve(_map(response), openapi)
            text = _text(resolved.get("description"))
            schema = _content_schema(resolved)
            lines.append(f"| {status} | {_cell(text)}{f' ({schema})' if schema else ''} |")
    return lines


def _schema(name: str, schema: Json) -> list[str]:
    lines = [f"### {name}"]
    description = " ".join(_text(schema.get("description")).split())
    if description:
        lines += ["", description]
    properties = _map(schema.get("properties"))
    if properties:
        required = {_text(item) for item in _list(schema.get("required"))}
        lines += ["", "| Field | Type | Required | Description |", "| --- | --- | --- | --- |"]
        lines += [
            f"| `{field}` | {_type(_map(spec))} | {'yes' if field in required else 'no'} | "
            f"{_cell(_text(_map(spec).get('description')))} |"
            for field, spec in properties.items()
        ]
    elif schema.get("enum"):
        lines += ["", "One of: " + ", ".join(f"`{_text(v)}`" for v in _list(schema.get("enum")))]
    return lines


def _type(spec: Json) -> str:
    ref = _text(spec.get("$ref"))
    if ref:
        return ref.rsplit("/", 1)[-1]
    kind = spec.get("type")
    if kind == "array":
        return f"array of {_type(_map(spec.get('items')))}"
    if isinstance(kind, list):
        return " or ".join(_text(k) for k in kind)
    return _text(kind) or "object"


def _content_schema(holder: Json) -> str:
    for media in _map(holder.get("content")).values():
        return _type(_map(_map(media).get("schema")))
    return ""


def _resolve(item: Json, openapi: Json) -> Json:
    """Follows one local ``$ref`` such as ``#/components/parameters/CalendarIdPath``."""
    ref = _text(item.get("$ref"))
    if not ref.startswith("#/"):
        return item
    target: object = openapi
    for part in ref[2:].split("/"):
        target = _map(target).get(part)
    return _map(target)


def _cell(text: str) -> str:
    return " ".join(text.split()).replace("|", "\\|")


def _map(value: object) -> Json:
    return cast("Json", value) if isinstance(value, Mapping) else {}


def _list(value: object) -> list[object]:
    return list(value) if isinstance(value, list) else []


def _text(value: object) -> str:
    return value if isinstance(value, str) else "" if value is None else str(value)
