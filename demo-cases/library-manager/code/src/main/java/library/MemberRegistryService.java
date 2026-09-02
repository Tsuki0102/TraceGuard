package library;

import java.util.HashMap;
import java.util.Map;

/**
 * 读者注册服务（REQ-002）。
 * 需求：读者注册时，必须校验姓名不为空且手机号为11位数字，否则注册失败并返回错误信息。
 */
public class MemberRegistryService {

    private final Map<Long, Member> members = new HashMap<>();
    private long idSeq = 1;

    /**
     * 读者注册：非空校验姓名不能为空；校验手机号为11位数字。
     * 校验失败时注册失败并返回错误信息。
     */
    public String registerMember(String name, String phone) {
        // 非空校验：读者姓名不能为空
        if (name == null || name.isEmpty()) {
            return "错误：读者姓名不能为空，注册失败 [REQ-002: 读者注册时，必须校验姓名不为空且手机号为11位数字，否则注册失败并返回错误信息]";
        }
        // 格式校验：手机号必须为11位数字
        if (phone == null || !phone.matches("\\d{11}")) {
            return "错误：手机号必须为11位数字，注册失败 [REQ-002: 读者注册时，必须校验姓名不为空且手机号为11位数字，否则注册失败并返回错误信息]";
        }
        Member member = new Member(idSeq, name, phone);
        members.put(member.getId(), member);
        idSeq++;
        return "读者注册成功：" + name + "，已校验姓名不为空且手机号为11位数字";
    }

    /** 按编号查询读者。 */
    public Member findMember(Long memberId) {
        return members.get(memberId);
    }
}
