package configurations;

public class ConfigParser {
    // WARNING: this is a tiny, tolerant parser for simple config files (objects, arrays, numbers, strings, booleans, null)
    static Map<String,Object> parse(String s) {
        return (Map<String,Object>) new Parser(s).parseValue();
    }
    static class Parser {
        String s; int i=0; int n;
        Parser(String s){this.s=s; n=s.length();}
        void ws(){while(i<n && Character.isWhitespace(s.charAt(i))) i++;}
        Object parseValue(){ ws(); if(i>=n) return null; char c=s.charAt(i);
            if (c=='{') return parseObj();
            if (c=='[') return parseArray();
            if (c=='"') return parseString();
            if (c=='t' || c=='f') return parseBool();
            if (c=='n') {i+=4; return null;}
            return parseNumber();
        }
        Map<String,Object> parseObj(){ Map<String,Object> m = new HashMap<>(); i++; ws(); while(i<n && s.charAt(i)!='}'){
            String k = parseString(); ws(); if(s.charAt(i)==':') i++; ws(); Object v = parseValue(); m.put(k,v); ws(); if(s.charAt(i)==',') {i++; ws();} }
            i++; return m; }
        ArrayList<Object> parseArray(){ ArrayList<Object> a = new ArrayList<>(); i++; ws(); while(i<n && s.charAt(i)!=']'){ a.add(parseValue()); ws(); if(s.charAt(i)==','){i++; ws();} } i++; return a; }
        String parseString(){ StringBuilder b=new StringBuilder(); i++; while(i<n){char c=s.charAt(i++); if(c=='"') break; if(c=='\\'){ if(i<n){char e=s.charAt(i++); b.append(e);} } else b.append(c);} return b.toString(); }
        Object parseBool(){ if (s.startsWith("true", i)) {i+=4; return Boolean.TRUE;} i+=5; return Boolean.FALSE; }
        Number parseNumber(){ int start=i; while(i<n && "0123456789+-.eE".indexOf(s.charAt(i))>=0) i++; String t = s.substring(start,i); if(t.contains(".")|| t.contains("e")||t.contains("E")) return Double.parseDouble(t); return Long.parseLong(t); }
    }
}