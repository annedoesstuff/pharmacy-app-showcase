package pharmacy.checkout;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.salespointframework.inventory.MultiInventory;
import org.salespointframework.order.OrderCompletionFailure;
import org.salespointframework.order.OrderLine;
import org.salespointframework.order.OrderStatus;
import org.salespointframework.quantity.Quantity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import pharmacy.order.PharmacyInventoryItem;
import pharmacy.order.PharmacyOrder;
import pharmacy.order.PharmacyOrder.LabOrderStatus;
import pharmacy.order.PharmacyOrder.OrderType;
import pharmacy.order.PharmacyOrderManagement;

@Controller
@PreAuthorize("isAuthenticated()")
public class BackorderController {

	private final PharmacyOrderManagement orderManagement;
	private final MultiInventory<PharmacyInventoryItem> inventory;

	public BackorderController(PharmacyOrderManagement orderManagement, MultiInventory<PharmacyInventoryItem> inventory) {
		this.orderManagement = orderManagement;
		this.inventory = inventory;
	}

	@GetMapping("/offenebestellungen")
	public String showBackorders(Model model) {
		var backorders = orderManagement.findBy(OrderStatus.PAID).stream()
			.filter(order -> order.getType() == OrderType.MEDICATION)
			.filter(order -> order.getLabStatus() == LabOrderStatus.OPEN)
			.collect(Collectors.toList());

		Map<String, Boolean> orderAvailability = new HashMap<>();
		for (PharmacyOrder order : backorders) {
			orderAvailability.put(order.getId().toString(), isOrderFulfillable(order));
		}

		model.addAttribute("backorderList", backorders);
		model.addAttribute("orderAvailability", orderAvailability);
		return "backorder-list";
	}

	private boolean isOrderFulfillable(PharmacyOrder order) {
		for (OrderLine orderLine : order.getOrderLines()) {
			Quantity totalQty = inventory
				.findByProductIdentifier(orderLine.getProductIdentifier())
				.getTotalQuantity();

			if (totalQty.isLessThan(orderLine.getQuantity())) {
				return false;
			}
		}
		return true;
	}


	@PostMapping("/offenebestellungen/abschliessen")
	public String completeBackorder(@RequestParam("orderId") PharmacyOrder order, RedirectAttributes redirectAttributes) {

		if (order == null) {
			redirectAttributes.addFlashAttribute("errorMessage", "Bestellung nicht gefunden.");
			return "redirect:/offenebestellungen";
		}

		// check if in stock
		if (order.getType() != OrderType.MEDICATION || order.getLabStatus() != LabOrderStatus.OPEN || order.getOrderStatus() != OrderStatus.PAID) {
			redirectAttributes.addFlashAttribute("errorMessage", "Diese Bestellung kann nicht auf diesem Weg abgeschlossen werden.");
			return "redirect:/offenebestellungen";
		}

		try {
			orderManagement.completeOrder(order);
			orderManagement.pickupOrder(order);

			redirectAttributes.addFlashAttribute("successMessage", "Bestellung #" + order.getId() + " wurde erfolgreich abgeschlossen.");

		} catch (OrderCompletionFailure e) {
			redirectAttributes.addFlashAttribute("errorMessage", "Abschluss für Bestellung #" + order.getId() + " fehlgeschlagen: Die Artikel sind nicht auf Lager.");
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("errorMessage", "Ein unerwarteter Fehler ist aufgetreten: " + e.getMessage());
		}

		return "redirect:/offenebestellungen";
	}
}
