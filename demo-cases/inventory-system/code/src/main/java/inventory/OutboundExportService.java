package inventory;

import java.util.List;

/**
 * 出库记录导出服务。
 * @author inventory-demo
 */
public class OutboundExportService {

    private final InventoryStore store;

    public OutboundExportService(InventoryStore store) {
        this.store = store;
    }

    /**
     * 导出出库记录为文本文件。
     * 需求 REQ-007: 出库记录导出：支持将全部出库记录导出为文本文件。
     * @param targetPath 目标文件路径
     * @return 导出结果信息
     */
    public String exportOutboundRecords(String targetPath) throws Exception {
        List<String> records = store.allOutboundRecords();
        java.io.PrintWriter writer = new java.io.PrintWriter(targetPath, "UTF-8");
        try {
            // 将全部出库记录逐行写入文本文件
            for (String record : records) {
                writer.println(record);
            }
        } finally {
            // 关闭输出流，释放资源
            writer.close();
        }
        return "出库记录导出成功，共 " + records.size() + " 条，目标文件 " + targetPath;
    }
}
