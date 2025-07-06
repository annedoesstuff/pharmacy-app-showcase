package pharmacy.welcome;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

import org.javamoney.moneta.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.salespointframework.inventory.MultiInventory;
import org.salespointframework.order.Cart;
import org.salespointframework.order.Order;
import org.salespointframework.order.OrderStatus;
import org.salespointframework.quantity.Quantity;
import org.salespointframework.useraccount.Password;
import org.salespointframework.useraccount.Role;
import org.salespointframework.useraccount.UserAccountManagement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import pharmacy.catalog.Medication;
import pharmacy.catalog.MedicationCatalog;
import pharmacy.order.PharmacyInventoryItem;
import pharmacy.order.PharmacyOrder;
import pharmacy.order.PharmacyOrderManagement;

import java.time.LocalDate;
import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
class CheckoutControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MedicationCatalog medicationCatalog;

	@Autowired
	private MultiInventory<PharmacyInventoryItem> inventory;

	@Autowired
	private PharmacyOrderManagement orderManagement;

	@Autowired
	private UserAccountManagement userAccountManagement;

	private Medication aspirin; // Non-prescription
	private Medication antibiotic; // Prescription
	private Cart cart;

	@BeforeEach
	void setUp() {
		inventory.deleteAll();
		orderManagement.findAll(Pageable.unpaged()).forEach(orderManagement::delete);
		medicationCatalog.deleteAll();


		aspirin = medicationCatalog.findByBarcode("ASPIRIN123")
			.orElseGet(() -> medicationCatalog.save(new Medication("ASPIRIN123", "Aspirin", Money.of(4.99, "EUR"), "Stk", false)));
		antibiotic = medicationCatalog.findByBarcode("ANTIBIO789")
			.orElseGet(() -> medicationCatalog.save(new Medication("ANTIBIO789", "Antibiotikum", Money.of(15.50, "EUR"), "Stk", true)));

		userAccountManagement.findByUsername("testuser")
			.orElseGet(() -> userAccountManagement.create("testuser", Password.UnencryptedPassword.of("password"), Role.of("BOSS")));

		userAccountManagement.findByUsername("anonymous")
			.orElseGet(() -> userAccountManagement.create("anonymous", Password.UnencryptedPassword.of("password")));

		cart = new Cart();
	}

	@Test
	@WithMockUser(username = "testuser")
	void showsCheckoutPageWithCorrectTotals() throws Exception {
		cart.addOrUpdateItem(aspirin, 5);
		cart.addOrUpdateItem(antibiotic, 2);

		mockMvc.perform(get("/checkout").sessionAttr("checkoutCart", cart))
			.andExpect(status().isOk())
			.andExpect(view().name("checkout"))
			.andExpect(model().attributeExists("checkoutCart"))
			.andExpect(model().attributeExists("totalPriceCash"))
			.andExpect(model().attributeExists("totalPriceInsurance"))
			.andExpect(model().attribute("totalPriceCash", Money.of(55.95, "EUR"))); // (5 * 4.99) + (2 * 15.50)
	}

	@Test
	@WithMockUser(username = "testuser")
	void addsItemToCartAndRedirects() throws Exception {
		mockMvc.perform(post("/checkout/add")
				.param("barcode", aspirin.getBarcode())
				.param("quantity", "3")
				.sessionAttr("checkoutCart", cart)
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/checkout"))
			.andExpect(flash().attributeExists("successMessage"));

		assertThat(cart.isEmpty()).isFalse();
		assertThat(cart.getQuantity(aspirin.getId())).isEqualTo(Quantity.of(3));
	}

	@Test
	void addPrescriptionItemToCartFailsInSelfCheckout() throws Exception {
		mockMvc.perform(post("/checkout/add")
				.param("barcode", antibiotic.getBarcode()) // Prescription medication
				.param("quantity", "1")
				.sessionAttr("checkoutCart", cart)
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/checkout"))
			.andExpect(flash().attributeExists("errorMessage"));

		assertThat(cart.isEmpty()).isTrue();
	}

	@Test
	@WithMockUser(username = "testuser")
	void finishOrderWithItemsInStockAndOnBackorder() throws Exception {
		inventory.save(new PharmacyInventoryItem(aspirin, Quantity.of(10), LocalDate.now().plusYears(1)));

		cart.addOrUpdateItem(aspirin, 2); // Should be in stock
		cart.addOrUpdateItem(antibiotic, 1); // Should be backordered

		mockMvc.perform(post("/checkout/finish")
				.param("paymentMethod", "INSURANCE")
				.param("prescriptionNumber", "RX12345")
				.sessionAttr("checkoutCart", cart)
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/checkout/bill"))
			.andExpect(flash().attribute("hasInStockItems", true))
			.andExpect(flash().attribute("hasBackorderItems", true))
			.andExpect(flash().attributeExists("billInStockItems"))
			.andExpect(flash().attributeExists("billBackorderItems"));

		List<PharmacyOrder> orders = orderManagement.findAll(Pageable.unpaged()).toList();
		assertThat(orders).hasSize(2);

		Order inStockOrder = orders.stream().filter(o -> o.getOrderStatus() == OrderStatus.COMPLETED).findFirst().orElse(null);
		assertThat(inStockOrder).isNotNull();
		assertThat(inStockOrder.getOrderLines()).hasSize(1);
		assertThat(inStockOrder.getOrderLines().iterator().next().getProductName()).isEqualTo(aspirin.getName());

		Order backorder = orders.stream().filter(o -> o.getOrderStatus() == OrderStatus.PAID).findFirst().orElse(null);
		assertThat(backorder).isNotNull();
		assertThat(backorder.getOrderLines()).hasSize(1);
		assertThat(backorder.getOrderLines().iterator().next().getProductName()).isEqualTo(antibiotic.getName());

		assertThat(cart.isEmpty()).isTrue();
	}

	@Test
	@WithMockUser(username = "testuser")
	void finishOrderWithCashPaymentOnlyInStock() throws Exception {
		inventory.save(new PharmacyInventoryItem(aspirin, Quantity.of(5), LocalDate.now().plusYears(1)));
		cart.addOrUpdateItem(aspirin, 3);

		mockMvc.perform(post("/checkout/finish")
				.param("paymentMethod", "CASH")
				.sessionAttr("checkoutCart", cart)
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/checkout/bill"))
			.andExpect(flash().attribute("hasInStockItems", true))
			.andExpect(flash().attribute("hasBackorderItems", false));

		List<PharmacyOrder> orders = orderManagement.findAll(Pageable.unpaged()).toList();
		assertThat(orders).hasSize(1);
		assertThat(orders.get(0).getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(orders.get(0).getTotal()).isEqualTo(Money.of(14.97, "EUR"));
	}

	@Test
	void showBillPageRedirectsIfNoOrderFinished() throws Exception {
		mockMvc.perform(get("/checkout/bill"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/checkout"));
	}
}
