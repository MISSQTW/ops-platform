package com.lqbz.ops.compat;

import java.util.*;
import java.util.function.Supplier;

/** Java 8 下不可变集合与文本处理，保留原配置快照的只读语义。 */
public final class Java8 {
  private Java8() { }
  @SafeVarargs public static <T> List<T> list(T... values) { return listCopy(Arrays.asList(values)); }
  public static <T> List<T> listCopy(Collection<? extends T> values) {
    List<T> copy = new ArrayList<>();
    for (T value : values) copy.add(Objects.requireNonNull(value));
    return Collections.unmodifiableList(copy);
  }
  @SafeVarargs public static <T> Set<T> set(T... values) {
    Set<T> copy = new LinkedHashSet<>();
    for (T value : values) if (!copy.add(Objects.requireNonNull(value))) throw new IllegalArgumentException("集合元素重复");
    return Collections.unmodifiableSet(copy);
  }
  public static <T> Set<T> setCopy(Collection<? extends T> values) { return Collections.unmodifiableSet(new LinkedHashSet<>(values)); }
  @SuppressWarnings("unchecked") public static <K,V> Map<K,V> map(Object... entries) {
    if (entries.length % 2 != 0) throw new IllegalArgumentException("映射键值不完整");
    Map<K,V> copy = new LinkedHashMap<>();
    for (int i=0;i<entries.length;i+=2) {
      K key=(K) Objects.requireNonNull(entries[i]); V value=(V) Objects.requireNonNull(entries[i+1]);
      if (copy.put(key,value)!=null) throw new IllegalArgumentException("映射键重复");
    }
    return Collections.unmodifiableMap(copy);
  }
  public static <K,V> Map<K,V> mapCopy(Map<? extends K,? extends V> values) {
    Map<K,V> copy=new LinkedHashMap<>();
    values.forEach((key,value)->copy.put(Objects.requireNonNull(key),Objects.requireNonNull(value)));
    return Collections.unmodifiableMap(copy);
  }
  public static boolean isBlank(String value) { return value.codePoints().allMatch(Character::isWhitespace); }
  public static <T> T eval(Supplier<T> value) { return value.get(); }
  public static String hex(byte[] bytes) { StringBuilder result=new StringBuilder(); for(byte value:bytes) result.append(String.format("%02x",value & 255)); return result.toString(); }
  public static java.util.concurrent.ThreadFactory daemonFactory(String name) {
    java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
    return task->{Thread thread=new Thread(task,name+"-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;};
  }
}
