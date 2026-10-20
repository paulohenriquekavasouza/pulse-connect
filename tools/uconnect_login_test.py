import getpass
import os
import sys

try:
    from py_uconnect import brands, Client
except ImportError:
    print("py-uconnect nao encontrado.")
    print("Instale com:  pip install py-uconnect")
    print("Ou:           pip install git+https://github.com/hass-uconnect/py-uconnect")
    sys.exit(1)


def candidate_brands():
    names = [
        "FIAT_US",
        "FIAT_EU",
        "FIAT_ASIA",
        "FIAT_CANADA",
    ]
    found = []
    for name in names:
        brand = getattr(brands, name, None)
        if brand is not None:
            found.append((name, brand))
    return found


def main():
    email = os.environ.get("UCONNECT_EMAIL") or input("E-mail da conta Uconnect/Fiat: ").strip()
    password = os.environ.get("UCONNECT_PASSWORD") or getpass.getpass("Senha: ")

    if not email or not password:
        print("E-mail e senha sao obrigatorios.")
        sys.exit(1)

    tried = candidate_brands()
    print(f"\nTestando {len(tried)} regiao(oes): {', '.join(n for n, _ in tried)}\n")

    any_success = False
    for name, brand in tried:
        print(f"=== {name} ===")
        try:
            client = Client(email, password, "", brand=brand)
            client.refresh()
            vehicles = client.get_vehicles()
        except Exception as error:
            print(f"  falhou: {type(error).__name__}: {error}\n")
            continue

        if not vehicles:
            print("  login OK, mas nenhum veiculo retornado.\n")
            continue

        any_success = True
        print(f"  SUCESSO: {len(vehicles)} veiculo(s) nesta regiao.")
        for vehicle in vehicles.values():
            print(vehicle.to_json(indent=2))
        print()

    if not any_success:
        print("Nenhuma regiao logou com esta conta.")
        print("Confira e-mail e senha no app oficial. Contas brasileiras usam a regiao FIAT_US.")


if __name__ == "__main__":
    main()
