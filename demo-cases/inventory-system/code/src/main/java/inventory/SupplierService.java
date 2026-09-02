package inventory;

import java.util.HashMap;
import java.util.Map;

/**
 * 供应商管理服务。
 * @author inventory-demo
 */
public class SupplierService {

    private final Map<Long, String> suppliers = new HashMap<>();
    private long idSeq = 1;

    /**
     * 新增供应商。
     * 需求 REQ-008: 新增供应商时必须校验供应商名称不为空且联系电话为11位数字，否则新增失败并返回错误信息。
     * @param name 供应商名称，供应商名称不能为空
     * @param phone 联系电话，联系电话必须为11位数字
     * @return 新增结果，新增失败时返回错误信息
     */
    public String addSupplier(String name, String phone) {
        // 校验供应商名称不为空
        if (name == null || name.isEmpty()) {
            return "错误：供应商名称不能为空，新增失败";
        }
        // 校验联系电话为11位数字
        if (phone == null || !phone.matches("\\d{11}")) {
            return "错误：联系电话必须为11位数字，新增失败";
        }
        suppliers.put(idSeq, name);
        idSeq++;
        return "供应商新增成功：" + name;
    }
}
