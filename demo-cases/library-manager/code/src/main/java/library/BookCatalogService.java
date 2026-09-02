package library;

import java.util.HashMap;
import java.util.Map;

/**
 * 图书入库服务（REQ-001）。
 * 需求：图书入库时，必须校验图书标题不为空且馆藏数量大于0，否则入库失败并返回错误信息。
 */
public class BookCatalogService {

    private final Map<Long, Book> books = new HashMap<>();
    private long idSeq = 1;

    /**
     * 图书入库：校验图书标题不为空，校验馆藏数量大于0。
     * 库存校验：入库数量必须大于0，否则入库失败返回错误信息。
     */
    public String addBook(String title, String author, int copies) {
        // 非空校验：图书标题不能为空
        if (title == null || title.isEmpty()) {
            return "错误：图书标题不能为空，入库失败 [REQ-001: 图书入库时，必须校验图书标题不为空且馆藏数量大于0，否则入库失败并返回错误信息]";
        }
        // 数值阈值校验：馆藏数量（库存）必须大于0
        if (copies <= 0) {
            return "错误：馆藏数量必须大于0，入库失败 [REQ-001: 图书入库时，必须校验图书标题不为空且馆藏数量大于0，否则入库失败并返回错误信息]";
        }
        Book book = new Book(idSeq, title, author, copies);
        books.put(book.getId(), book);
        idSeq++;
        return "图书入库成功：" + title + "，馆藏数量 " + copies + "，已校验图书标题不为空且馆藏数量大于0";
    }

    /** 按编号查询图书。 */
    public Book findBook(Long bookId) {
        return books.get(bookId);
    }

    /** 全部馆藏图书。 */
    public java.util.List<Book> allBooks() {
        return new java.util.ArrayList<>(books.values());
    }

    /** 修改馆藏库存数量。 */
    public void updateStock(Long bookId, int stock) {
        Book book = books.get(bookId);
        if (book != null && stock >= 0) {
            book.setCopies(stock);
        }
    }
}
