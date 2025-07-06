package pharmacy.welcome;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.javamoney.moneta.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.salespointframework.inventory.MultiInventory;
import org.salespointframework.order.OrderStatus;
import org.salespointframework.payment.Cash;
import org.salespointframework.quantity.Quantity;
import org.salespointframework.useraccount.Password;
import org.salespointframework.useraccount.Role;
import org.salespointframework.useraccount.UserAccount;
import org.salespointframework.useraccount.UserAccountManagement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import pharmacy.catalog.Medication;
import pharmacy.catalog.MedicationCatalog;
import pharmacy.order.PharmacyInventoryItem;
import pharmacy.order.PharmacyOrder;
import pharmacy.order.PharmacyOrder.LabOrderStatus;
import pharmacy.order.PharmacyOrder.OrderType;
import pharmacy.order.PharmacyOrderManagement;

import java.time.LocalDate;
import java.util.Optional;

@SpringBootTest
@AutoConfigureMockMvc
class BackorderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PharmacyOrderManagement orderManagement;

	@Autowired
	private MedicationCatalog medicationCatalog;

	@Autowired
	private MultiInventory<PharmacyInventoryItem> inventory;

	@Autowired
	private UserAccountManagement userAccountManagement;

	private UserAccount user;
	private Medication ibuprofen;
	private PharmacyOrder testOrder;

	@BeforeEach
	void setUp() {
		user = userAccountManagement.findByUsername("testuser")
			.orElseGet(() -> userAccountManagement.create("testuser", Password.UnencryptedPassword.of("password"), Role.of("BOSS")));

		ibuprofen = medicationCatalog.findByBarcode("12345678")
			.orElseGet(() -> medicationCatalog.save(new Medication("12345678", "Ibuprofen 400", Money.of(5.99, "EUR"), "Box", true)));

		testOrder = new PharmacyOrder(OrderType.MEDICATION, user.getId(), Cash.CASH);
		testOrder.addOrderLine(ibuprofen, Quantity.of(2));
		orderManagement.payOrder(testOrder); // Status is now PAID
		//order is no t completed yet, simulating a backorder
	}

	@Test
	@WithMockUser(username = "testuser")
	void showBackordersPageCorrectly() throws Exception {
		mockMvc.perform(get("/offenebestellungen"))
			.andExpect(status().isOk())
			.andExpect(view().name("backorder-list"))
			.andExpect(model().attributeExists("backorderList"))
			.andExpect(model().attributeExists("orderAvailability"));
	}

	@Test
	@WithMockUser(username = "testuser")
	void completeBackorderSuccessfullyWhenInStock() throws Exception {
		PharmacyInventoryItem item = new PharmacyInventoryItem(ibuprofen, Quantity.of(10), LocalDate.now().plusYears(1));
		inventory.save(item);

		mockMvc.perform(post("/offenebestellungen/abschliessen")
				.param("orderId", testOrder.getId().toString())
				.with(csrf())) // include CSRF token for POST requests
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/offenebestellungen"))
			.andExpect(flash().attributeExists("successMessage"));

		Optional<PharmacyOrder> completedOrderOpt = orderManagement.get(testOrder.getId());
		assertThat(completedOrderOpt).isPresent();
		assertThat(completedOrderOpt.get().getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(completedOrderOpt.get().getLabStatus()).isEqualTo(LabOrderStatus.PICKED_UP);
	}

	@Test
	@WithMockUser(username = "testuser")
	void completeBackorderFailsWhenNotInStock() throws Exception {
		inventory.findByProduct(ibuprofen).forEach(inventory::delete);

		mockMvc.perform(post("/offenebestellungen/abschliessen")
				.param("orderId", testOrder.getId().toString())
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/offenebestellungen"))
			.andExpect(flash().attributeExists("errorMessage"));

		Optional<PharmacyOrder> orderOpt = orderManagement.get(testOrder.getId());
		assertThat(orderOpt).isPresent();
		assertThat(orderOpt.get().getOrderStatus()).isEqualTo(OrderStatus.PAID);
	}
}
