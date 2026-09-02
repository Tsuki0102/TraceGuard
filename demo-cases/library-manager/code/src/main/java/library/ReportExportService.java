package library;

import java.io.FileOutputStream;
import java.util.List;

/**
 * 借阅报表导出服务（REQ-008）。
 * 需求：借阅报表支持导出为文本文件，将全部借阅记录逐行写入目标文件。
 */
public class ReportExportService {

    /**
     * 导出借阅报表：将全部借阅记录逐行写入目标文本文件。
     */
    public void exportLoans(List<Loan> loans, String targetPath) throws Exception {
        FileOutputStream out = new FileOutputStream(targetPath);
        for (Loan loan : loans) {
            String line = loan.getId() + "," + loan.getMemberId() + "," + loan.getBookId()
                    + "," + loan.getBorrowDate() + "," + loan.getDueDate() + "\n";
            out.write(line.getBytes("UTF-8"));
        }
        out.flush();
    }
}
