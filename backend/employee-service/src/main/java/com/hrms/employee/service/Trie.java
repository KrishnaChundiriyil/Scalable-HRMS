package com.hrms.employee.service;
import java.util.*;

/** Prefix trie used for employee/department autocomplete. */
public class Trie {
 private static class Node {Map<Character,Node> children=new HashMap<>();boolean end;String word;}
 private final Node root=new Node();
 public void insert(String word){if(word==null||word.isBlank())return;Node n=root;String clean=word.trim();for(char ch:clean.toLowerCase(Locale.ROOT).toCharArray())n=n.children.computeIfAbsent(ch,k->new Node());n.end=true;n.word=clean;}
 public List<String> search(String prefix,int limit){Node n=root;for(char ch:prefix.toLowerCase(Locale.ROOT).toCharArray()){n=n.children.get(ch);if(n==null)return List.of();}List<String> out=new ArrayList<>();collect(n,out,limit);return out;}
 private void collect(Node n,List<String> out,int limit){if(n.end&&out.size()<limit)out.add(n.word);if(out.size()>=limit)return;for(Node child:n.children.values()){collect(child,out,limit);if(out.size()>=limit)return;}}
}
