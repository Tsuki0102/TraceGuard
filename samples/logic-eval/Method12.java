public class Method12 {
    public List<String> collectDistinctTags(List<String> tags) {
        Set<String> unique = new HashSet<>();
        for (String t : tags) {
            unique.add(t);
        }
        return new ArrayList<>(unique);
    }
}
