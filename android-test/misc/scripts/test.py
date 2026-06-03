import re
import json
import requests
from typing import Optional, Dict, Any


USER_AGENT = "NutritionApp/1.0 (tru60117@gmail.com)"


def parse_quantity_from_text(text: str) -> Optional[str]:
    """
    Ищет массу/объем в тексте:
    950 мл, 1 л, 500 г, 0.9 кг и т.д.
    """
    if not text:
        return None

    text = text.lower().replace(",", ".")

    pattern = r"(\d+(?:\.\d+)?)\s*(мл|ml|л|l|г|g|кг|kg)\b"
    match = re.search(pattern, text)

    if not match:
        return None

    value, unit = match.groups()

    unit_map = {
        "ml": "мл",
        "мл": "мл",
        "l": "л",
        "л": "л",
        "g": "г",
        "г": "г",
        "kg": "кг",
        "кг": "кг",
    }

    return f"{value} {unit_map.get(unit, unit)}"


def get_nutrient(nutriments: Dict[str, Any], key: str):
    """
    Безопасно достает нутриент.
    Например:
    energy-kcal_100g
    proteins_100g
    fat_100g
    carbohydrates_100g
    """
    return nutriments.get(key)


def get_package_size(product: Dict[str, Any]) -> Optional[str]:
    """
    Достает объем/массу упаковки.
    Приоритет:
    1. quantity
    2. product_quantity + product_quantity_unit
    3. serving_size
    4. regex из названия
    """
    quantity = product.get("quantity")
    if quantity:
        return quantity

    product_quantity = product.get("product_quantity")
    product_quantity_unit = product.get("product_quantity_unit")

    if product_quantity and product_quantity_unit:
        return f"{product_quantity} {product_quantity_unit}"

    if product_quantity:
        return str(product_quantity)

    serving_size = product.get("serving_size")
    if serving_size:
        return serving_size

    possible_texts = [
        product.get("product_name", ""),
        product.get("generic_name", ""),
        product.get("abbreviated_product_name", ""),
    ]

    for text in possible_texts:
        parsed = parse_quantity_from_text(text)
        if parsed:
            return parsed

    return None


def get_product_by_barcode(barcode: str) -> Optional[Dict[str, Any]]:
    url = f"https://world.openfoodfacts.org/api/v2/product/{barcode}.json"

    fields = ",".join([
        "code",
        "product_name",
        "generic_name",
        "abbreviated_product_name",
        "brands",
        "quantity",
        "product_quantity",
        "product_quantity_unit",
        "serving_size",
        "nutriments",
        "categories",
        "image_url",
    ])

    headers = {
        "User-Agent": USER_AGENT,
    }

    response = requests.get(
        url,
        headers=headers,
        params={"fields": fields},
        timeout=15,
    )

    response.raise_for_status()
    data = response.json()

    if data.get("status") != 1:
        return None

    product = data.get("product", {})
    nutriments = product.get("nutriments", {})

    result = {
        "barcode": barcode,
        "name": product.get("product_name"),
        "brand": product.get("brands"),
        "category": product.get("categories"),
        "package_size": get_package_size(product),
        "serving_size": product.get("serving_size"),
        "image_url": product.get("image_url"),

        "per_100g": {
            "kcal": get_nutrient(nutriments, "energy-kcal_100g"),
            "protein_g": get_nutrient(nutriments, "proteins_100g"),
            "fat_g": get_nutrient(nutriments, "fat_100g"),
            "carbs_g": get_nutrient(nutriments, "carbohydrates_100g"),
            "sugars_g": get_nutrient(nutriments, "sugars_100g"),
            "salt_g": get_nutrient(nutriments, "salt_100g"),
        },

        "raw_quantity_fields": {
            "quantity": product.get("quantity"),
            "product_quantity": product.get("product_quantity"),
            "product_quantity_unit": product.get("product_quantity_unit"),
            "serving_size": product.get("serving_size"),
        }
    }

    return result


if __name__ == "__main__":
    barcode = "4640017350512"

    product = get_product_by_barcode(barcode)

    if product is None:
        print("Продукт не найден")
    else:
        print(json.dumps(product, ensure_ascii=False, indent=2))