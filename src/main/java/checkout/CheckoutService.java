package pharmacy.checkout;

import java.math.BigDecimal;
import java.util.Optional;

import javax.money.MonetaryAmount;

import org.javamoney.moneta.Money;
import org.salespointframework.core.Currencies;
import org.salespointframework.order.Cart;
import org.salespointframework.order.CartItem;
import org.salespointframework.order.OrderLine;
import org.salespointframework.quantity.Quantity;
import org.springframework.stereotype.Service;

import pharmacy.catalog.Medication;
import pharmacy.catalog.MedicationCatalog;
import pharmacy.order.PharmacyOrder;
import pharmacy.order.PharmacyOrder.OrderType;

@Service
public class CheckoutService {
	private static final Money CO_PAYMENT_MIN = Money.of(5, Currencies.EURO);
	private static final Money CO_PAYMENT_MAX = Money.of(10, Currencies.EURO); // per medication
	private static final BigDecimal CO_PAYMENT_RATE = new BigDecimal("0.10");

	private final MedicationCatalog medicationCatalog;

	CheckoutService(MedicationCatalog medicationCatalog) {
		this.medicationCatalog = medicationCatalog;
	}

	/**
	 * Calculates co-payment for all prescription medications in cart.
	 * RULE: 10% of total price, min 5€, max 10€ PER MEDICATION.
	 *
	 * @return total co-payment as a Money-Object.
	 */
	public Money calculateCoPayment(Cart cart, OrderType type, BigDecimal margin) {
		Money totalCoPayment = Money.of(0, Currencies.EURO);

		if (type.equals(OrderType.LAB)) {
			totalCoPayment = getLineCoPayment(cart.getPrice().add(cart.getPrice().multiply(margin)), Quantity.of(1));
		} else {
			for (CartItem item : cart) {
				// does it need perscription to be viable for health insurance???
				if (item.getProduct() instanceof Medication medication && medication.getNeedsPrescription()) {
					Money coPayment = getLineCoPayment(item.getPrice(), item.getQuantity());
					totalCoPayment = totalCoPayment.add(coPayment);
				}
			}
		}

		return totalCoPayment;
	}

	/**
	 * Overload for orders
	 */
	public Money calculateCoPayment(PharmacyOrder order) {
		Money totalCoPayment = Money.of(0, Currencies.EURO);

		if (order.getType().equals(OrderType.LAB)) {
			totalCoPayment = getLineCoPayment(order.getTotal(), Quantity.of(1));
		} else {
			for (OrderLine line : order.getOrderLines()) {
				Optional<Medication> product = medicationCatalog.findById(line.getProductIdentifier());
				if (product.isPresent()) {
					Medication p = product.get();
					if (p.getNeedsPrescription()) {
						totalCoPayment = totalCoPayment.add(getLineCoPayment(line.getPrice(), line.getQuantity()));
					}
				}
			}
		}

		return totalCoPayment;
	}

	private Money getLineCoPayment(MonetaryAmount linePrice, Quantity quantity) {
		Money pricePerUnit = (Money) linePrice.divide(quantity.getAmount());
		Money coPaymentPerUnit = pricePerUnit.multiply(CO_PAYMENT_RATE);

		if (coPaymentPerUnit.isLessThan(CO_PAYMENT_MIN))
			coPaymentPerUnit = CO_PAYMENT_MIN;
		else if (coPaymentPerUnit.isGreaterThan(CO_PAYMENT_MAX))
			coPaymentPerUnit = CO_PAYMENT_MAX;
		return coPaymentPerUnit.multiply(quantity.getAmount());
	}
}
