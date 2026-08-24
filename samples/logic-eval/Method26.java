public class Method26 {
    public void writeReportToStream(OutputStream out) throws IOException {
        String report = buildReport();
        out.write(report.getBytes());
        out.close();
    }
    private String buildReport() { return "report"; }
}
