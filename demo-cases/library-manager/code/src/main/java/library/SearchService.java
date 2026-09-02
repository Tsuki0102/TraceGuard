package library;

import java.util.ArrayList;
import java.util.List;

/**
 * 图书检索服务（REQ-007）。
 * 需求：图书检索支持按关键词对书名进行模糊匹配，将关键词拼入查询语句执行检索并返回结果列表。
 */
public class SearchService {

    private final BookCatalogService catalog;

    public SearchService(BookCatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * 按关键词检索图书：将关键词拼入查询语句执行检索，模糊匹配书名并返回结果列表。
     */
    public List<Book> searchByKeyword(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>();
        }
        // REQ-007: 图书检索支持按关键词对书名进行模糊匹配，将关键词拼入查询语句执行检索并返回结果列表
        String query = "SELECT * FROM books WHERE title LIKE '%" + keyword + "%'";
        return executeQuery(query);
    }

    /** 执行拼接后的图书查询语句。 */
    private List<Book> executeQuery(String sql) {
        List<Book> result = new ArrayList<>();
        String keyword = extractLikeValue(sql);
        for (Book book : catalog.allBooks()) {
            if (book.getTitle() != null && book.getTitle().contains(keyword)) {
                result.add(book);
            }
        }
        return result;
    }

    private String extractLikeValue(String sql) {
        int start = sql.indexOf("LIKE '%") + 7;
        int end = sql.lastIndexOf("%'");
        if (start < 7 || end <= start) {
            return "";
        }
        return sql.substring(start, end);
    }
}
