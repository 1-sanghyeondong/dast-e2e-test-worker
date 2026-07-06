#!/usr/bin/env python3
"""
OpenAPI 스펙 패치 스크립트.

OpenAPI Generator는 parameters 에 schema/content 가 없으면 UNKNOWN_PARAMETER_NAME 을 생성하며
컴파일 에러를 유발합니다. 이 스크립트는 schema/content 가 모두 없는 파라미터에
{ type: string } 더미 스키마를 삽입해 생성 코드가 컴파일되도록 합니다.

사용법: python3 fix-openapi-spec.py <spec-file.json>
"""
import json
import sys


def fix_missing_schemas(spec: dict) -> int:
    fixed = 0
    paths = spec.get("paths", {})

    for path, path_item in paths.items():
        for method, operation in path_item.items():
            if not isinstance(operation, dict):
                continue
            for param in operation.get("parameters", []):
                if not isinstance(param, dict) or "$ref" in param:
                    continue
                if "schema" not in param and "content" not in param:
                    param["schema"] = {"type": "string"}
                    fixed += 1
                    print(
                        f"  [fix] {method.upper()} {path} "
                        f"param '{param.get('name', '?')}' → added schema:string"
                    )

    # components/parameters 도 패치
    for name, param in spec.get("components", {}).get("parameters", {}).items():
        if not isinstance(param, dict) or "$ref" in param:
            continue
        if "schema" not in param and "content" not in param:
            param["schema"] = {"type": "string"}
            fixed += 1
            print(f"  [fix] components/parameters/{name} → added schema:string")

    return fixed


def fix_date_limit_time_fields(spec: dict) -> int:
    """DateLimit.startTime / endTime 타입 수정.

    실제 API 응답이 'HH:mm' 형식 문자열을 반환하는데 스펙은 int64 로 선언되어
    Jackson 역직렬화 실패가 발생합니다. string 으로 교정합니다.
    """
    fixed = 0
    schemas = spec.get("components", {}).get("schemas", {})
    dl = schemas.get("DateLimit", {})
    props = dl.get("properties", {})

    for field in ("startTime", "endTime"):
        if field in props and props[field].get("type") == "integer":
            props[field] = {"type": "string"}
            fixed += 1
            print(f"  [fix] DateLimit.{field}: integer/int64 → string (HH:mm 응답 대응)")

    return fixed


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <spec-file.json>")
        sys.exit(1)

    filepath = sys.argv[1]
    print(f"Patching spec: {filepath}")

    with open(filepath, encoding="utf-8") as f:
        spec = json.load(f)

    count = fix_missing_schemas(spec)
    count += fix_date_limit_time_fields(spec)

    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(spec, f, indent=2, ensure_ascii=False)

    print(f"Done. {count} item(s) patched.")


if __name__ == "__main__":
    main()
