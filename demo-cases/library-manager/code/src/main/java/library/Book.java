package library;

/** 图书实体：馆藏图书信息。 */
public class Book {
    private final Long id;
    private final String title;
    private final String author;
    private int copies;

    public Book(Long id, String title, String author, int copies) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.copies = copies;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public int getCopies() { return copies; }
    public void setCopies(int copies) { this.copies = copies; }
}
