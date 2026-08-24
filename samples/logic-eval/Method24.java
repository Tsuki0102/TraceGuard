public class Method24 {
    public String callRemoteService(String url) {
        try {
            return httpClient.get(url);
        } catch (Exception e) {
            return "降级默认结果";
        }
    }
    static HttpClient httpClient = new HttpClient();
    static class HttpClient { String get(String u) { return "ok"; } }
}
