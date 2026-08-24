public class Method22 {
    public String readConfigFile(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) {
            throw new BusinessException("配置文件缺失: " + path);
        }
        return new String(Files.readAllBytes(f.toPath()));
    }
    static class BusinessException extends RuntimeException { BusinessException(String m){super(m);} }
}
