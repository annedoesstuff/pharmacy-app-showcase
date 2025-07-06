package pharmacy.checkout;

import java.util.Optional;

import org.salespointframework.catalog.Catalog;
import org.salespointframework.catalog.Product;
import org.salespointframework.inventory.MultiInventory;
import org.salespointframework.order.Cart;
import org.salespointframework.order.CartItem;
import org.salespointframework.quantity.Quantity;
import org.springframework.ui.Model;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pharmacy.order.PharmacyInventoryItem;

public abstract class CartController<P extends Product, C extends Catalog<P>> {
	private final String baseURL;
	private final String cartName;
	private final boolean enableInventory;
	private final C catalog;
	private final MultiInventory<PharmacyInventoryItem> inventory;

	public CartController(
			String baseURL,
			String cartName,
			boolean enableInventory,
			C catalog,
			MultiInventory<PharmacyInventoryItem> inventory) {
		Assert.notNull(baseURL, "baseURL must not be null");
		Assert.notNull(cartName, "cartName must not be null");
		Assert.notNull(catalog, "Catalog must not be null");
		Assert.notNull(inventory, "Inventory must not be null!");
		this.baseURL = baseURL;
		this.cartName = cartName;
		this.enableInventory = enableInventory;
		this.catalog = catalog;
		this.inventory = inventory;
	}

	// update/delete medication form cart ---------------------------------------
	@PostMapping("/updateQuantity")
	public String updateQuantity(
			Model model,
			@RequestParam String itemId,
			@RequestParam int quantity,
			RedirectAttributes redirectAttributes) {
		Cart cart = (Cart) model.getAttribute(cartName);
		if (quantity < 0) {
			redirectAttributes.addFlashAttribute("errorMessage", "Menge darf nicht negativ sein.");
			return "redirect:" + baseURL;
		}

		Optional<CartItem> itemOpt = cart.getItem(itemId);
		if (itemOpt.isPresent()) {
			CartItem item = itemOpt.get();

			if (enableInventory) {
				Optional<String> checkResult = checkInventory(redirectAttributes, item, quantity);
				if (checkResult.isPresent()) {
					return checkResult.get();
				}
			}

			cart.removeItem(itemId);
			if (quantity == 0) {
				redirectAttributes.addFlashAttribute("infoMessage", item.getProductName() + " entfernt.");
			} else {
				cart.addOrUpdateItem(item.getProduct(), Quantity.of(quantity));
				redirectAttributes.addFlashAttribute("infoMessage",
						"Menge für " + item.getProductName() + " auf " + quantity + " geändert.");
			}
		} else {
			redirectAttributes.addFlashAttribute("errorMessage", "Artikel nicht im Warenkorb gefunden.");
		}

		return "redirect:" + baseURL;
	}

	// clear cart
	@PostMapping("/clear")
	public String clearCart(Model model, RedirectAttributes redirectAttributes) {
		Cart cart = (Cart) model.getAttribute(cartName);
		if (!cart.isEmpty()) {
			cart.clear();
			redirectAttributes.addFlashAttribute("infoMessage", "Warenkorb geleert.");
		}
		return "redirect:" + baseURL;
	}

	private Optional<String> checkInventory(RedirectAttributes redirectAttributes, CartItem item,
			int requestedQuantity) {
		Quantity currentQuantity = getCurrentQuantity(item);
		if (currentQuantity.isLessThan(Quantity.of(requestedQuantity))) {
			redirectAttributes.addFlashAttribute("errorMessage",
					"Bestand für '" + item.getProductName() + "' reicht nicht für Menge " + requestedQuantity
							+ ". Bestand: "
							+ currentQuantity.getAmount().intValue());
			return Optional.of("redirect:" + baseURL);
		} else {
			return Optional.empty();
		}
	}

	private Quantity getCurrentQuantity(CartItem item) {
		return inventory.findByProduct(item.getProduct()).getTotalQuantity();
	}
}
