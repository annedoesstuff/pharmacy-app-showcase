# Showcase: Java Spring Pharmacy POS & Management System

This repo shows my main contributions to a pharmacy management and Point-of-Sale system, developed as part of a university group project. The app has inventory management, a checkout process with prescription handling, backorder management, a lab for making new medication and various reporting functionalities.

## Project Context

This project was a collaborative effort developed by a team of four students for a university course. My teammates were Anton L., Hamza B., and Eqbal A. (BEST TEAM EVER YEAAAHHH <3). The full source code is sadly hosted in a private university repository. 

---

## Screenshots

| Dashboard Overview | Checkout Interface |
| :---: | :---: |
| ![Screenshot 2025-07-06 120947](https://github.com/user-attachments/assets/4e8a3b08-1b86-4009-96e3-de0f4727e4b7) | ![Screenshot 2025-07-06 121125](https://github.com/user-attachments/assets/706dc570-0052-4484-9e34-03a871f15cc3) |

| Backorder Management | Generated Customer Bill |
| :---: | :---: |
| ![Screenshot 2025-07-06 121234](https://github.com/user-attachments/assets/b6a01893-bd7b-425e-bcbc-5f8fec1cac7d) | ![Screenshot 2025-07-06 121209](https://github.com/user-attachments/assets/a6e5520f-860d-4f07-9eb2-64463538dfb1) |


## My Main Contributions

I was responsible for making several critical features from end-to-end. My focus was on the checkout and order management lifecycle.

### 1. Checkout Feature
* **Complex Cart & Order Logic:** Developed the `CheckoutController.java` to manage all checkout operations. A key challenge was splitting the user's cart into two separate orders (so we can reuse it for the lab site): one for immediately available items and another for backordered items, which was handled in the `/finish` endpoint
* **Health Insurance Implementation:** In the `CheckoutService.java` is the logic for calculating statutory co-payments for prescription items, based on rules (10% of price, min €5, max €10 per item)
* **Payment Integration:** Handled multiple payment methods, including Cash, Insurance (Kassenrezept), and Practice-internal billing

### 2. Backorder Management
I built the system for managing customer orders for items that were not in stock at the time of purchase.

* **Backend Logic:** Developed the `BackorderController.java` to show all open backorders and handle their completion
* **Dynamic UI:** The corresponding view (`backorder-list.html`) dynamically checks inventory availability for each order. It enables the "Complete Order" button only when all required items are back in stock

### 3. Dynamic Frontend
I did the core user interfaces using Thymeleaf and Semantic UI.

* **Component-Based UI:** Created reusable UI fragments, such as the shopping cart (`cart.html`), which is embedded in multiple views (lab and checkout)
* **Dynamic Rendering:** Dynamic views for the checkout process (`checkout.html`), the final customer bill (`bill.html`), and the main dashboard (`dashboard.html`)
* **Role-Based Security:** We used Spring Security's Thymeleaf extras (`sec:authorize`) to conditionally render UI elements based on user roles (e.g., showing administrative functions only to 'BOSS' or 'EMPLOYEE' roles)

### 4. Tests
We used test to ensure stability and correctness of features by writing integration tests using JUnit 5 and MockMvc.

* **Testing Critical Paths:** My tests in `CheckoutControllerTest.java` cover critical scenarios, such as correctly splitting an order into in-stock and backorder parts, handling different payment methods, and preventing the sale of prescription-only drugs at the self-checkout terminal
* **Checking Logic:** The tests in `BackorderControllerTest.java` validate both the success and failure scenarios for completing backorders, ensuring that orders cannot be completed if items are not in stock

## Technology Stack

- **Backend:** Java, Spring Boot, Spring Security, Salespoint Framework, JPA / Hibernate 
- **Frontend:** Thymeleaf, Semantic UI, HTML, CSS 
- **Database:** H2
- **Testing:** JUnit 5, MockMvc, Spring Boot Test 

## How to Explore The Showcase

The key files are organized in a simplified directory structure.

* `/src/main/java/pharmacy/checkout/`: Core backend logic for the checkout and backorder process
* `/src/main/resources/templates/`: Thymeleaf HTML templates I designed
* `/src/test/java/pharmacy/welcome/`: Integration tests I wrote
* `/docs/`: The original documentation for the project
