package jbse.val;

import static jbse.common.Type.REFERENCE;

import jbse.mem.Klass;

/**
 * Class for symbolic {@link Reference}s to the {@link Heap}.
 * 
 * @author Pietro Braione
 */
public abstract class ReferenceSymbolic extends Reference implements Symbolic {
    /** The creation history point of this symbol. */
    private final HistoryPoint historyPoint;

    /** The static type of the reference (or null). */
    private final String staticType;

    /**
     * Constructor.
     * 
     * @param staticType a {@link String}, the static type of the
     *        variable from which this reference originates, 
     *        or {@code null} if this object refers to a {@link Klass}.
     * @param historyPoint the current {@link HistoryPoint}.
     */
    ReferenceSymbolic(String staticType, HistoryPoint historyPoint) {
    	super(REFERENCE);
        this.staticType = staticType;
        this.historyPoint = historyPoint;
    }
    
    /**
     * Returns the static type of this reference.
     * 
     * @return a {@code String}.
     */
    public final String getStaticType() {
    	return this.staticType;
    }
    
    /**
     * Returns the root {@link ReferenceSymbolic} (i.e., the topmost
     * contained) where this {@link ReferenceSymbolic} originates from.
     * 
     * @return a {@link KlassPseudoReference}, or a {@link ReferenceSymbolicApply}, 
     *         or a {@link ReferenceSymbolicLocalVariable}.
     */
    public abstract ReferenceSymbolic root();

    @Override
    public final String getValue() {
    	return toString();
    }
    
    @Override
    public final HistoryPoint historyPoint() {
        return this.historyPoint;
    }

    /**
     * {@inheritDoc}
     * For {@link ReferenceSymbolic} values it will always return {@code true}.
     */
    @Override
    public final boolean isSymbolic() {
    	return true;
    }
}
