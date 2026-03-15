tail -n +2 /Users/waabox/Downloads/12168411_2026_03_07.csv | grep -v '^$' | while IFS= read -r cart_id; do 
	echo "=== Rollback cartId: $cart_id ===" 
done
