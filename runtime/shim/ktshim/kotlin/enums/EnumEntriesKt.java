package ktshim.kotlin.enums;

import ktshim.kotlin.jvm.functions.Function0;

public final class EnumEntriesKt {
    private EnumEntriesKt() {}
    public static EnumEntries enumEntries(Enum[] values) { return new EnumEntriesList(values); }
    public static EnumEntries enumEntries(Function0 values) { return new EnumEntriesList((Object[]) values.invoke()); }
}
