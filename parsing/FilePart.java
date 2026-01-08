package parsing;

import java.util.List;

public class FilePart {

    public String name;
    public String filename;
    public String contentType;

    public boolean isEmpty() {
        return isBlank(name) || isBlank(filename) || isBlank(contentType);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }


    public boolean sameAs(FilePart other) {
        if (other == null) return false;

        return safeEquals(this.name, other.name)
            && safeEquals(this.filename, other.filename)
            && safeEquals(this.contentType, other.contentType);
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    public static FilePart getIfAllSame(List<FilePart> parts) {
        if (parts == null || parts.isEmpty()) return null;
        FilePart first = parts.get(0);
        if (first == null || first.isEmpty()) return null;

        for (int i = 1; i < parts.size(); i++) {
            FilePart fp = parts.get(i);
            if (fp == null || fp.isEmpty()) {
                return null;
            }
            if (!first.sameAs(fp)) {
                return null;
            }
        }
        return first; 
    }
}

