package app.services;

import app.model.DeliveryRequest;
import app.repositories.DeliveryRequestRepository;
import app.requests.OrderRequestBody;
import app.model.Order;
import app.model.Product;
import app.repositories.OrderRepository;
import app.repositories.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.transaction.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;

@Service("orderService")
@EnableScheduling
public class OrderService
{
    public static final Duration cancelPeriod = Duration.ofMinutes(1);

    private OrderRepository orders;
    private ProductRepository products;
    private DeliveryRequestRepository deliveries;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setData(OrderRepository orders, ProductRepository products, DeliveryRequestRepository deliveries, PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.products = products;
        this.deliveries = deliveries;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }


    public void startOnOrderPaidTransaction(Order order)
    {
        if (!order.getConfirmed())
            throw new OrderPaymentException("Order has not been confirmed yet");
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                order.setPayed(true);
                Product product = order.getProduct();
                product.setBookedAmount(product.getBookedAmount() - 1);
                product.setAmount(product.getAmount() - 1);
                if (order.getMethodOfDelivery().equals("courier")){
                    DeliveryRequest request = deliveries.save(new DeliveryRequest(order, "На складе", "Требуется назначить курьера"));
                }
                if (order.getMethodOfDelivery().equals("takeout")){
                    DeliveryRequest request = deliveries.save(new DeliveryRequest(order, "На складе", null));
                }
                orders.save(order);
                products.save(product);
            }
        });
    }


    public Order startAddOrderTransaction(OrderRequestBody rawOrder)
    {
        Order order = rawOrder.constructOrder(products);
        Product product = order.getProduct();
        try {
         return transactionTemplate.execute(status -> {
             product.setBookedAmount(order.getProduct().getBookedAmount() + 1);
              if (product.getAmount() < product.getBookedAmount())
                    throw new ProductBookingException("This product is either unavailable or fully booked");
              products.save(product);
              return orders.save(order);
            });
        }
        catch (Exception ignored){};
        return null;
    }



    @Scheduled(fixedRate = 1000 * 60)
    public void removedUnpaidOverdueOrders()
    {
        System.out.println("Launching overdue order cancellation...");
        int cancelled = 0;

        for (Order order : orders.findAll()) {
            if (order.getConfirmed() && !order.getCanceled() && !order.getPayed() && isOverCancelTime(order.getConfirmationDate()))
            {
                order.setCanceled(true);
                orders.save(order);
                System.out.println("Cancelling order #" + order.getOrderId() + " - payment is overdue");
                cancelled++;
            }
        }

        System.out.println("Cancelled " + cancelled + " orders.");
    }



    private static boolean isOverCancelTime(LocalDateTime dateTime)
    {
        Duration timePassed = Duration.between(dateTime, LocalDateTime.now());
        return timePassed.compareTo(cancelPeriod) >= 0;
    }
}
