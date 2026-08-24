public class Method08 {
    public void updateInventory(Long productId, int quantity, InventoryDao dao) {
        dao.setQuantity(productId, quantity);
    }
    static class InventoryDao { void setQuantity(Long id, int q) {} }
}
