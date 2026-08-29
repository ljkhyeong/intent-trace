#!/usr/bin/env python3
import json
import pathlib
import subprocess
import sys


def fail(message: str) -> None:
    raise SystemExit(message)


def network_names(service: dict) -> set[str]:
    networks = service.get("networks", {})
    if isinstance(networks, dict):
        return set(networks)
    if isinstance(networks, list):
        return set(networks)
    fail("Compose service의 networks 형식을 확인할 수 없습니다.")


def main() -> None:
    root = pathlib.Path(__file__).resolve().parent.parent
    environment_file = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".env.team.example")
    if not environment_file.is_absolute():
        environment_file = root / environment_file

    result = subprocess.run(
        ["docker", "compose", "--env-file", str(environment_file), "config", "--format", "json"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    config = json.loads(result.stdout)
    services = config.get("services", {})
    expected_networks = {
        "postgres": {"data"},
        "app": {"data", "edge"},
        "caddy": {"edge"},
    }

    if set(services) != set(expected_networks):
        fail("Compose service는 postgres, app, caddy만 포함해야 합니다.")
    for name, expected in expected_networks.items():
        if network_names(services[name]) != expected:
            fail(f"{name} service의 network 경계가 예상과 다릅니다.")

    published_services = {name for name, service in services.items() if service.get("ports")}
    if published_services != {"caddy"}:
        fail("host port는 caddy service만 공개해야 합니다.")
    if not config.get("networks", {}).get("data", {}).get("internal"):
        fail("data network는 외부 통신이 차단돼야 합니다.")

    for name in ("postgres", "caddy"):
        image = services[name].get("image", "")
        if "@sha256:" not in image:
            fail(f"{name} image는 sha256 digest로 고정해야 합니다.")

    print("Compose service·network·port·image 경계를 확인했습니다.")


if __name__ == "__main__":
    main()
