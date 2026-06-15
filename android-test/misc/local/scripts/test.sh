

if ! lsusb | grep -q Xiaomi; then
    BUSID=$(usbipd list | awk '/Redmi Note 8 Pro, ADB Interface/ {print $1}')

    if [ -n "$BUSID" ]; then
        echo "Подключение..."
        usbipd attach --wsl --busid "$BUSID"
    else
        echo "Телефон не найден в usbipd list"
    fi
else 
	echo "Телефон уже подключен"
fi