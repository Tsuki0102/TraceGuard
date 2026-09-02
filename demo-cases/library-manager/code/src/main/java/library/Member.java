package library;

/** 读者实体：注册读者信息。 */
public class Member {
    private final Long id;
    private final String name;
    private final String phone;

    public Member(Long id, String name, String phone) {
        this.id = id;
        this.name = name;
        this.phone = phone;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
}
