package pharmacy.checkout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.javamoney.moneta.Money;
import org.salespointframework.core.Currencies;
import org.salespointframework.inventory.MultiInventory;
import org.salespointframework.order.Cart;
import org.salespointframework.order.CartItem;
import org.salespointframework.payment.Cash;
import org.salespointframework.payment.PaymentMethod;
import org.salespointframework.quantity.Quantity;
import org.salespointframework.useraccount.UserAccount;
import org.salespointframework.useraccount.UserAccountManagement;
import org.salespointframework.useraccount.web.LoggedIn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pharmacy.catalog.Medication;
import pharmacy.catalog.MedicationCatalog;
import pharmacy.order.Insurance;
import pharmacy.order.PharmacyInventoryItem;
import pharmacy.order.PharmacyOrder;
import pharmacy.order.PharmacyOrder.OrderType;
import pharmacy.order.PharmacyOrderManagement;
import pharmacy.order.PracticePayment;

@Controller
@SessionAttributes("checkoutCart") // keeps cart in session
@RequestMapping("/checkout")
class CheckoutController extends CartController<Medication, MedicationCatalog> {
	private final MedicationCatalog catalog;
	private final MultiInventory<PharmacyInventoryItem> inventory;
	private final CheckoutService checkoutService;
	private final PharmacyOrderManagement orderManagement;
	private final UserAccountManagement userAccountManagement;

	@Autowired
	CheckoutController(
			MedicationCatalog catalog,
			MultiInventory<PharmacyInventoryItem> inventory,
			PharmacyOrderManagement orderManagement,
			CheckoutService checkoutService,
			UserAccountManagement userAccountManagement) {

		super("/checkout", "checkoutCart", true, catalog, inventory);
		Assert.notNull(catalog, "MedicationCatalog must not be null");
		Assert.notNull(orderManagement, "OrderManagement must not be null");
		Assert.notNull(inventory, "Inventory must not be null!");
		Assert.notNull(checkoutService, "CheckoutService must not be null!");
		Assert.notNull(userAccountManagement, "UserAccountManagement must not be null!");

		this.catalog = catalog;
		this.inventory = inventory;
		this.checkoutService = checkoutService;
		this.orderManagement = orderManagement;
		this.userAccountManagement = userAccountManagement;
	}

	@ModelAttribute("checkoutCart")
	Cart initializeCart() {
		return new Cart();
	}

	private int getAvailableStock(Medication medication) {
		return inventory
				.findByProduct(medication)
				.getTotalQuantity()
				.getAmount()
				.intValue();
	}

	@GetMapping("")
	public String checkoutView(Model model, @ModelAttribute("checkoutCart") Cart cart) {
		if (!cart.isEmpty()) {

			Money sumInsuranceProducts = checkoutService.calculateCoPayment(cart, OrderType.MEDICATION, BigDecimal.ZERO);
			Money sumNonInsuranceProducts = Money.of(0, Currencies.EURO);
			for (CartItem item : cart) {
				if (item.getProduct() instanceof Medication medication && !medication.getNeedsPrescription()) {
					sumNonInsuranceProducts = sumNonInsuranceProducts.add(item.getPrice());
				}
			}

			Money coPayment = sumInsuranceProducts.add(sumNonInsuranceProducts);

			model.addAttribute("calculatedCoPayment", sumInsuranceProducts);
			model.addAttribute("totalPriceInsurance", coPayment);
			model.addAttribute("totalPriceCash", cart.getPrice());

			// info for cart.html to display availability per item
			Map<String, Integer> itemAvailableQuantities = new HashMap<>();
			Map<String, Integer> itemBackorderQuantities = new HashMap<>();
			for (CartItem item : cart) {
				Medication medication = (Medication) item.getProduct();
				int requested = item.getQuantity().getAmount().intValue();
				int available = getAvailableStock(medication);
				itemAvailableQuantities.put(item.getId(), Math.min(requested, available));
				if (requested > available) {
					itemBackorderQuantities.put(item.getId(), requested - available);
				}
			}
			model.addAttribute("itemAvailableQuantities", itemAvailableQuantities);
			model.addAttribute("itemBackorderQuantities", itemBackorderQuantities);
		}
		// remove global outOfStockItem attributes
		// might not need TODO
		model.asMap().remove("outOfStockItem");
		model.asMap().remove("outOfStockQty");

		return "checkout";
	}

	// add medication to cart ---------------------------------------------------
	@PostMapping("/add")
	public String addToCart(
			@ModelAttribute("checkoutCart") Cart cart,
			@RequestParam String barcode,
			@RequestParam(defaultValue = "1") int quantity,
			RedirectAttributes redirectAttributes,
			@LoggedIn Optional<UserAccount> userAccount) {

		if (barcode == null || barcode.trim().isEmpty() || quantity <= 0) {
			redirectAttributes.addFlashAttribute("errorMessage", "Ungültige Eingabe für Barcode oder Menge.");
			return "redirect:/checkout";
		}

		Optional<Medication> medicationOpt = catalog.findByBarcode(barcode);
		if (medicationOpt.isPresent()) {
			Medication medication = medicationOpt.get();

			// For self-checkout, only allow non-prescription medications
			if (userAccount.isEmpty() && medication.getNeedsPrescription()) {
				redirectAttributes.addFlashAttribute("errorMessage",
						"Dieses Medikament erfordert ein Rezept und kann nicht an der Selbstbedienungskasse gekauft werden.");
				return "redirect:/checkout";
			}

			// add full requested quantity to cart
			cart.addOrUpdateItem(medication, Quantity.of(quantity));
			redirectAttributes.addFlashAttribute("successMessage",
					quantity + "x " + medication.getName() + " zum Warenkorb hinzugefügt (Verfügbarkeit wird noch geprüft).");
		} else {
			redirectAttributes.addFlashAttribute("errorMessage", "Artikel mit Barcode '" + barcode + "' nicht gefunden.");
		}
		return "redirect:/checkout";
	}

	// update Quantity override with same logic as /add
	@Override
	@PostMapping("/updateQuantity")
	public String updateQuantity(Model model, @RequestParam String itemId, @RequestParam int quantity,
			RedirectAttributes redirectAttributes) {

		Cart cart = (Cart) model.getAttribute("checkoutCart");
		if (quantity < 0) {
			redirectAttributes.addFlashAttribute("errorMessage", "Menge darf nicht negativ sein.");
			return "redirect:/checkout";
		}

		Optional<CartItem> itemOpt = cart.getItem(itemId);
		if (itemOpt.isPresent()) {
			CartItem item = itemOpt.get();
			Medication medication = (Medication) item.getProduct();

			cart.removeItem(itemId);

			if (quantity > 0) { // Remove item
				cart.addOrUpdateItem(medication, Quantity.of(quantity));
				redirectAttributes.addFlashAttribute("infoMessage",
						"Menge für " + medication.getName() + " auf " + quantity + " geändert (Verfügbarkeit prüfen).");
			} else {
				redirectAttributes.addFlashAttribute("infoMessage", medication.getName() + " aus dem Warenkorb entfernt.");
			}
		} else {
			redirectAttributes.addFlashAttribute("errorMessage", "Artikel nicht im Warenkorb gefunden.");
		}
		return "redirect:/checkout";
	}

	// finish checkout -------------------------------------------
	@PostMapping("/finish")
	public String finishOrder(
			@ModelAttribute("checkoutCart") Cart cart,
			@RequestParam("paymentMethod") String paymentMethodStr,
			@RequestParam(required = false) String prescriptionNumber,
			RedirectAttributes redirectAttributes,
			@LoggedIn Optional<UserAccount> userAccount,
			Model model) {

		if (cart.isEmpty()) {
			redirectAttributes.addFlashAttribute("errorMessage", "Warenkorb ist leer.");
			return "redirect:/checkout";
		}

		UserAccount user = userAccount.orElseGet(() -> userAccountManagement.findByUsername("anonymous")
				.orElseThrow(() -> new IllegalStateException("Anonymous user account not found! Please create it.")));

		// for self-checkout, only CASH payment is allowed, no co-payment calculation
		boolean hasInsurance = paymentMethodStr.equalsIgnoreCase("INSURANCE");
		boolean praxisPay = paymentMethodStr.equalsIgnoreCase("PRACTICE");
		PaymentMethod paymentMethod;
		if (hasInsurance) {
			paymentMethod = Insurance.INSURANCE;
		} else if (praxisPay) {
			paymentMethod = PracticePayment.PRACTICE;
		} else
			paymentMethod = Cash.CASH;

		if (userAccount.isEmpty() && (hasInsurance || praxisPay)) {
			redirectAttributes.addFlashAttribute("errorMessage", "An der Selbstbedienungskasse ist nur Barzahlung möglich.");
			return "redirect:/checkout";
		}

		Cart inStockCart = new Cart();
		Cart backorderCart = new Cart();

		// -- split original cart into 2
		for (CartItem item : cart) {
			Medication medication = (Medication) item.getProduct();
			int requested = item.getQuantity().getAmount().intValue();
			int available = getAvailableStock(medication);

			if (requested > available) {
				if (available > 0) {
					inStockCart.addOrUpdateItem(medication, Quantity.of(available));
				}
				backorderCart.addOrUpdateItem(medication, Quantity.of(requested - available));
			} else {
				inStockCart.addOrUpdateItem(medication, Quantity.of(requested)); // or item.getQuantity() ??
			}
		}

		String inStockOrderId = null;
		String backorderOrderId = null;
		boolean inStockOrderCreated = false;
		boolean backorderOrderCreated = false;

		// -- process in stock items
		if (!inStockCart.isEmpty()) {
			try {
				PharmacyOrder orderInStock = new PharmacyOrder(OrderType.MEDICATION, user.getId(), paymentMethod);
				orderInStock.setPrescriptionNumber(prescriptionNumber);
				inStockCart.addItemsTo(orderInStock);
				orderManagement.payOrder(orderInStock);
				orderManagement.completeOrder(orderInStock);

				inStockOrderId = orderInStock.getId().toString();
				inStockOrderCreated = true;

				Money inStockTotal;

				if (hasInsurance) {
					Money coPaymentForInStock = checkoutService.calculateCoPayment(inStockCart, OrderType.MEDICATION, BigDecimal.ZERO);

					Money sumInStockNonInsurance = Money.of(0, Currencies.EURO);
					for (CartItem item : inStockCart) {
						if (item.getProduct() instanceof Medication medication && !medication.getNeedsPrescription()) {
							sumInStockNonInsurance = sumInStockNonInsurance.add(item.getPrice());
						}
					}
					inStockTotal = coPaymentForInStock.add(sumInStockNonInsurance);
					redirectAttributes.addFlashAttribute("billInStockCoPayment", coPaymentForInStock);
				} else {
					inStockTotal = (Money) inStockCart.getPrice();
				}
				redirectAttributes.addFlashAttribute("billInStockTotal", inStockTotal); //
				redirectAttributes.addFlashAttribute("billInStockItems", new ArrayList<>(inStockCart.toList())); //
				redirectAttributes.addFlashAttribute("billInStockOrderId", inStockOrderId); //

			} catch (Exception e) {
				redirectAttributes.addFlashAttribute("errorMessage",
						"Fehler bei der Bearbeitung der verfügbaren Artikel: " + e.getMessage());
			}
		}

		// -- process backorder
		if (!backorderCart.isEmpty()) {
			try {
				PharmacyOrder orderBackorder = new PharmacyOrder(OrderType.MEDICATION, user.getId(), paymentMethod);
				orderBackorder.setPrescriptionNumber(prescriptionNumber);
				backorderCart.addItemsTo(orderBackorder);
				orderManagement.payOrder(orderBackorder);
				// NO completeOrder for backorders because inventory not touched yet

				backorderOrderId = orderBackorder.getId().toString();
				backorderOrderCreated = true;

				Money backorderTotal;
				if (hasInsurance){
					Money coPaymentBackorder = checkoutService.calculateCoPayment(backorderCart, OrderType.MEDICATION, BigDecimal.ZERO);
					Money sumBackorderNonInsurance = Money.of(0, Currencies.EURO);
					for (CartItem item : backorderCart) {
						if (item.getProduct() instanceof Medication medication && !medication.getNeedsPrescription()) {
							sumBackorderNonInsurance = sumBackorderNonInsurance.add(item.getPrice());
						}
					}
					backorderTotal = coPaymentBackorder.add(sumBackorderNonInsurance);
					redirectAttributes.addFlashAttribute("billBackorderCoPayment", coPaymentBackorder);
					redirectAttributes.addFlashAttribute("billBackorderPriceOfNonPrescription", sumBackorderNonInsurance);
				} else {
					backorderTotal = (Money) backorderCart.getPrice();
				}

				redirectAttributes.addFlashAttribute("billBackorderItems", new ArrayList<>(backorderCart.toList()));
				redirectAttributes.addFlashAttribute("billBackorderTotal", backorderTotal);
				redirectAttributes.addFlashAttribute("billBackorderOrderId", backorderOrderId);
			} catch (Exception e) {
				redirectAttributes.addFlashAttribute("errorMessage",
						(redirectAttributes.getFlashAttributes().containsKey("errorMessage")
								? redirectAttributes.getFlashAttributes().get("errorMessage") + " "
								: "") + "Fehler bei der Bearbeitung der Nachbestellung: " + e.getMessage());
			}
		}

		if (!inStockOrderCreated && !backorderOrderCreated
				&& !redirectAttributes.getFlashAttributes().containsKey("errorMessage")) {
			redirectAttributes.addFlashAttribute("errorMessage", "Keine Artikel konnten verarbeitet werden.");
			return "redirect:/checkout";
		}

		redirectAttributes.addFlashAttribute("billPaymentMethod", paymentMethodStr);
		redirectAttributes.addFlashAttribute("hasInStockItems", inStockOrderCreated);
		redirectAttributes.addFlashAttribute("hasBackorderItems", backorderOrderCreated);

		cart.clear();

		return "redirect:/checkout/bill";
	}

	// show finished checkout bill ---------------------------------
	@GetMapping("/bill")
	public String billView(Model model) {
		if (!model.containsAttribute("hasInStockItems") || !model.containsAttribute("hasBackorderItems")) {
			boolean hasError = model.asMap().entrySet().stream()
					.anyMatch(entry -> entry.getKey().toLowerCase().contains("error"));
			if (!hasError) {
				return "redirect:/checkout";
			}
		}
		return "checkout-bill";
	}
}
