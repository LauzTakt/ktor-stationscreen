package ktshim.kotlin.enums;

final class EnumEntriesList extends ktshim.java.util.ArrayList implements EnumEntries {
    EnumEntriesList(Object[] values) { super(values.length); for (int i = 0; i < values.length; i++) add(values[i]); }
}
