package jbse.apps.run;

import static jbse.bc.Signatures.JAVA_MAP_CONTAINSKEY;
import static jbse.common.Type.BOOLEAN;
import static jbse.common.Type.REFERENCE;
import static jbse.common.Type.TYPEEND;
import static jbse.common.Type.binaryClassName;
import static jbse.common.Type.internalClassName;
import static jbse.common.Type.isPrimitiveOrVoidCanonicalName;
import static jbse.common.Type.toPrimitiveOrVoidInternalName;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodExitRequest;

import jbse.bc.Offsets;
import jbse.bc.Signature;
import jbse.common.exc.InvalidInputException;
import jbse.common.exc.UnexpectedInternalException;
import jbse.dec.DecisionProcedure;
import jbse.jvm.Runner;
import jbse.jvm.RunnerParameters;
import jbse.mem.State;
import jbse.mem.exc.ThreadStackEmptyException;
import jbse.val.Calculator;
import jbse.val.KlassPseudoReference;
import jbse.val.PrimitiveSymbolicApply;
import jbse.val.PrimitiveSymbolicHashCode;
import jbse.val.PrimitiveSymbolicMemberArrayLength;
import jbse.val.ReferenceSymbolic;
import jbse.val.ReferenceSymbolicMemberMapValue;
import jbse.val.Simplex;
import jbse.val.Symbolic;
import jbse.val.SymbolicApply;
import jbse.val.SymbolicLocalVariable;
import jbse.val.SymbolicMemberArray;
import jbse.val.SymbolicMemberField;

/**
 * {@link DecisionProcedureGuidance} that uses the installed JVM accessed via JDI to 
 * perform concrete execution. 
 */
public final class DecisionProcedureGuidanceJDI extends DecisionProcedureGuidance {
	/**
	 * Builds the {@link DecisionProcedureGuidanceJDI}.
	 *
	 * @param component the component {@link DecisionProcedure} it decorates.
	 * @param calc a {@link Calculator}.
	 * @param runnerParameters the {@link RunnerParameters} of the symbolic execution.
	 *        The constructor modifies this object by adding the {@link Runner.Actions}s
	 *        necessary to the execution.
	 * @param stopSignature the {@link Signature} of a method. The guiding concrete execution 
	 *        will stop at the entry of the first invocation of the method whose 
	 *        signature is {@code stopSignature}, and the reached state will be used 
	 *        to answer queries.
	 * @throws GuidanceException if something fails during creation (and the caller
	 *         is to blame).
	 * @throws InvalidInputException if {@code component == null}.
	 */
	public DecisionProcedureGuidanceJDI(DecisionProcedure component, Calculator calc, RunnerParameters runnerParameters, Signature stopSignature) 
	throws GuidanceException, InvalidInputException {
		this(component, calc, runnerParameters, stopSignature, 1);
	}

	/**
	 * Builds the {@link DecisionProcedureGuidanceJDI}.
	 *
	 * @param component the component {@link DecisionProcedure} it decorates.
	 * @param calc a {@link Calculator}.
	 * @param runnerParameters the {@link RunnerParameters} of the symbolic execution.
	 *        The constructor modifies this object by adding the {@link Runner.Actions}s
	 *        necessary to the execution.
	 * @param stopSignature the {@link Signature} of a method. The guiding concrete execution 
	 *        will stop at the entry of the {@code numberOfHits}-th nonrecursive invocation of 
	 *        the method whose signature is {@code stopSignature}, and the reached state will 
	 *        be used to answer queries.
	 * @param numberOfHits an {@code int} greater or equal to one.
	 * @throws GuidanceException if something fails during creation (and the caller
	 *         is to blame).
	 * @throws InvalidInputException if {@code component == null}.
	 */
	public DecisionProcedureGuidanceJDI(DecisionProcedure component, Calculator calc, RunnerParameters runnerParameters, Signature stopSignature, int numberOfHits) 
	throws GuidanceException, InvalidInputException {
		super(component, new JVMJDI(calc, runnerParameters, stopSignature, numberOfHits));
	}

	/**
	 * Calculates the number of nonrecursive hits of a method.
	 *  
	 * @param runnerParameters the {@link RunnerParameters} of a concrete execution.
	 * @param stopSignature the {@link Signature} of a method.
	 * @return an {@code int} that amounts to the total number of nonrecursive 
	 *         invocations of the method whose signature is {@code stopSignature} 
	 *         from the concrete execution started by {@code runnerParameters}.
	 * @throws GuidanceException if something fails during creation (and the caller
	 *         is to blame).
	 */
        public static int countNonRecursiveHits(RunnerParameters runnerParameters, Signature stopSignature) 
        throws GuidanceException {
                final JVMJDI jdiCompleteExecution = new JVMJDI(runnerParameters, stopSignature);
                return jdiCompleteExecution.hitCounter;
        }
        
	private static class JVMJDI extends JVM {
		private static final String ERROR_BAD_PATH = "Failed accessing through a memory access path: ";

		StreamRedirectThread outThread = null; 
		StreamRedirectThread errThread = null; 

		protected VirtualMachine vm;
		private BreakpointRequest breakpoint;
		protected int hitCounter;
		private  boolean valueDependsOnSymbolicApply;
		private int numOfFramesAtMethodEntry;
		protected Event currentExecutionPointEvent;        
		private Map<String, ReferenceType> alreadyLoadedClasses = new HashMap<>();

		private final RunnerParameters runnerParameters;
		private final Signature stopSignature;
		private final int stopSignatureNumberOfHits;
		
		// Handling of uninterpreted functions
		private Map<SymbolicApply, SymbolicApplyJVMJDI> symbolicApplyCache = new HashMap<>();
		private Map<String, List<String>> symbolicApplyOperatorOccurrences = new HashMap<>();
		private String currentHashMapModelMethod;
		
		public JVMJDI(RunnerParameters runnerParameters, Signature stopSignature) 
		throws GuidanceException {
			super(null, runnerParameters, stopSignature, Integer.MAX_VALUE);
			this.runnerParameters = runnerParameters;
			this.stopSignature = stopSignature;
			this.stopSignatureNumberOfHits = Integer.MAX_VALUE;
			this.vm = createVM();
			try {
				goToBreakpoint(stopSignature, 0, Integer.MAX_VALUE);			
			} catch (GuidanceException e) {
                                //obviates to inferior process leak
                                this.vm.process().destroyForcibly();
                                this.vm = null;
				return;
			}
			throw new GuidanceException("This constructor continues the execution up to termination, thus JDI will throw an exception eventually upon disconnecting.");
		}
		
		public JVMJDI(Calculator calc, RunnerParameters runnerParameters, Signature stopSignature, int numberOfHits) 
		throws GuidanceException {
			super(calc, runnerParameters, stopSignature, numberOfHits);
			this.runnerParameters = runnerParameters;
			this.stopSignature = stopSignature;
			this.stopSignatureNumberOfHits = numberOfHits;
			this.vm = createVM();
			goToBreakpoint(stopSignature, 0, numberOfHits);
			try 	{
				this.numOfFramesAtMethodEntry = getCurrentThread().frameCount();
			} catch (IncompatibleThreadStateException e) {
				throw new UnexpectedInternalException(e); 
			}
			this.outThread = redirect("Subproc stdout", this.vm.process().getInputStream(), System.out);
			this.errThread = redirect("Subproc stderr", this.vm.process().getErrorStream(), System.err);
		}
		
		private StreamRedirectThread redirect(String name, InputStream in, OutputStream out) {
			StreamRedirectThread t = new StreamRedirectThread(name, in, out);
			t.setDaemon(true);
			t.start();
			return t;
		}

		/**
		 * StreamRedirectThread is a thread which copies it's input to
		 * it's output and terminates when it completes.
		 *
		 * @author Robert Field
		 */
		private static class StreamRedirectThread extends Thread {

			private final Reader in;
			private final Writer out;

			private static final int BUFFER_SIZE = 2048;

			/**
			 * Set up for copy.
			 * @param name  Name of the thread
			 * @param in    Stream to copy from
			 * @param out   Stream to copy to
			 */
			StreamRedirectThread(String name, InputStream in, OutputStream out) {
				super(name);
				this.in = new InputStreamReader(in);
				this.out = new OutputStreamWriter(out);
				setPriority(Thread.MAX_PRIORITY - 1);
			}

			/**
			 * Copy.
			 */
			@Override
			public void run() {
				try {
					char[] cbuf = new char[BUFFER_SIZE];
					int count;
					while ((count = this.in.read(cbuf, 0, BUFFER_SIZE)) >= 0) {
					    this.out.write(cbuf, 0, count);
					}
					this.out.flush();
				} catch(IOException exc) {
					System.err.println("Child I/O Transfer - " + exc);
				}
			}

			public void flush() {
				try {
				    this.out.flush();
				} catch (IOException exc) {
					System.err.println("Child I/O Transfer - " + exc);
				}
			}
		}


		private VirtualMachine createVM() 
				throws GuidanceException {
			try {
				final Iterable<Path> classPath = this.runnerParameters.getClasspath().classPath();
				final ArrayList<String> listClassPath = new ArrayList<>();
				classPath.forEach(p -> listClassPath.add(p.toString()));
				final String stringClassPath = String.join(File.pathSeparator, listClassPath.toArray(new String[0]));
				final String mainClass = DecisionProcedureGuidanceJDILauncher.class.getName();
				final String targetClass = binaryClassName(this.runnerParameters.getMethodSignature().getClassName());
				final String startMethodName = this.runnerParameters.getMethodSignature().getName();
				return launchTarget("-classpath \"" + stringClassPath + "\" " + mainClass + " " + targetClass + " " + startMethodName);
			} catch (IOException e) {
				throw new GuidanceException(e);
			}
		}

		private VirtualMachine launchTarget(String mainArgs) throws GuidanceException {
			final LaunchingConnector connector = findLaunchingConnector();
			final Map<String, Connector.Argument> arguments = connectorArguments(connector, mainArgs);
			try {
				return connector.launch(arguments);
			} catch (IOException | IllegalConnectorArgumentsException | VMStartException exc) {
				throw new GuidanceException(exc);
			}
		}

		private Map<String, Connector.Argument> connectorArguments(LaunchingConnector connector, String mainArgs) {
			final Map<String, Connector.Argument> arguments = connector.defaultArguments();
			final Connector.Argument mainArg = arguments.get("main");
			if (mainArg == null) {
				throw new Error("Bad launching connector");
			}
			mainArg.setValue(mainArgs);
			return arguments;
		}

		protected void goToBreakpoint(Signature sig, int offset, int numberOfHits) throws GuidanceException {
			//System.out.println("*** moveJdiToCurrentExecutionPointOfJbse: " + jbseLocationAhead.sig + "::" + jbseLocationAhead.pc + " (occurrence " + numberOfHits + ")");

			//sets event requests
			final EventRequestManager mgr = this.vm.eventRequestManager();
			final ClassPrepareRequest  cprr = mgr.createClassPrepareRequest();
			cprr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
			cprr.enable();

			for (ReferenceType classType: this.vm.allClasses()) {
			    this.alreadyLoadedClasses.put(classType.name().replace('.', '/'), classType);
				//System.out.println("ClassLOADED: " + classType.name());
			}

			trySetBreakPoint(sig, offset);

			//executes
			this.vm.resume();

			final EventQueue queue = this.vm.eventQueue();
			boolean stopPointFound = false;
			while (!stopPointFound) {
				try {
					final EventSet eventSet = queue.remove();
					final EventIterator it = eventSet.eventIterator();
					while (!stopPointFound && it.hasNext()) {
						final Event event = it.nextEvent();
						handleClassPrepareEvents(event);
						if (this.breakpoint == null) {
							trySetBreakPoint(sig, offset);
						} else {
							stopPointFound = handleBreakpointEvents(event, numberOfHits);
						}
					}
					if (!stopPointFound) {
						eventSet.resume();
					}
				} catch (InterruptedException e) {
					throw new GuidanceException(e);
					//TODO is it ok?
				} catch (VMDisconnectedException e) {
					if (this.errThread != null) {
					    this.errThread.flush();
					}
					if (this.outThread != null) {
					    this.outThread.flush();
					}
					if (stopPointFound) {
						return; //must not try to disable event requests
					} else {
						throw new GuidanceException("while looking for " + sig + "::" + offset + " : " + e);
					}
				}
			}
			//disables event requests
			cprr.disable();
			if (this.breakpoint != null) {
				this.breakpoint.disable();
			}
		}

		private void handleClassPrepareEvents(Event event) {
			if (event instanceof ClassPrepareEvent) {
				ClassPrepareEvent evt = (ClassPrepareEvent) event;
				ReferenceType classType = evt.referenceType();
				this.alreadyLoadedClasses.put(classType.name().replace('.', '/'), classType);
				for (ReferenceType innerType: classType.nestedTypes()) {
				    this.alreadyLoadedClasses.put(innerType.name().replace('.', '/'), innerType);					
					//System.out.println("ClassPrepareEvent: Inner-class: " + innerType.name());
				}
				//System.out.println("ClassPrepareEvent: " + classType.name());
			}
		}

		private void trySetBreakPoint(Signature sig, int offset) throws GuidanceException {
			String stopClassName = sig.getClassName();
			String stopMethodName = sig.getName();
			String stopMethodDescr = sig.getDescriptor();
			if (this.alreadyLoadedClasses.containsKey(stopClassName)) {
				ReferenceType classType = this.alreadyLoadedClasses.get(stopClassName);
				List<Method> methods = classType.methodsByName(stopMethodName);
				for (Method m: methods) {
					if (stopMethodDescr.equals(m.signature())) {
						//System.out.println("** Set breakpoint at: " + m.locationOfCodeIndex(offset));
						final EventRequestManager mgr = this.vm.eventRequestManager();
						this.breakpoint = mgr.createBreakpointRequest(m.locationOfCodeIndex(offset));
						this.breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
						this.breakpoint.enable();
						this.hitCounter = 0;
						return;
					}
				}
				throw new GuidanceException("Cannot set breakpoint because there is no method " + stopClassName + "." + stopMethodName + stopMethodDescr);
			}
		}

		protected boolean handleBreakpointEvents(Event event, int numberOfHits) throws GuidanceException {
			if (this.breakpoint.equals(event.request())) {
				//System.out.println("Breakpoint: stopped at: " + event);
				this.currentExecutionPointEvent = event;
				++this.hitCounter;
				
				// We check if the breakpoint is at a recursive method call, and do not count it if so
				try {
					final HashSet<Method> seenMethods = new HashSet<>();
					for (int i = 0; i < this.numFramesFromRootFrameConcrete(); ++i) {
						final Method m = this.getCurrentThread().frame(i).location().method();
						if (seenMethods.contains(m)) {
							--this.hitCounter;
							break;
						}
					}
				} catch (IncompatibleThreadStateException e) {
					throw new GuidanceException("JDI failed to check the call stack at breakpoint while searching for the target method: " + e);
				}
				
				if (this.hitCounter == numberOfHits) {
					return true;
				}
			}
			return false;
		}

		private LaunchingConnector findLaunchingConnector() {
			final List<Connector> connectors = Bootstrap.virtualMachineManager().allConnectors();
			for (Connector connector : connectors) {
				if (connector.name().equals("com.sun.jdi.CommandLineLaunch")) {
					return (LaunchingConnector) connector;
				}
			}
			throw new Error("No launching connector");
		}

		private StackFrame rootFrameConcrete() throws IncompatibleThreadStateException, GuidanceException {
			final int numFramesFromRoot = numFramesFromRootFrameConcrete();
			final List<StackFrame> frameStack = getCurrentThread().frames();
			return frameStack.get(numFramesFromRoot);
		}

		protected int numFramesFromRootFrameConcrete() throws IncompatibleThreadStateException, GuidanceException {
			final int numFrames = this.getCurrentThread().frameCount() - this.numOfFramesAtMethodEntry;
			return numFrames;
		}

		@Override
		public String typeOfObject(ReferenceSymbolic origin) throws GuidanceException {
			final ObjectReference object;
			try {
				object = (ObjectReference) this.getValue(origin);
			} catch (IndexOutOfBoundsException e) {
				if (!origin.asOriginString().equals(e.getMessage())) {
					System.out.println("[JDI] WARNING: In DecisionProcedureGuidanceJDI.typeOfObject: " + origin.asOriginString() + " leads to invalid throw reference: " + e + 
							"\n ** Normally this happens when JBSE wants to extract concrete types for fresh-expands, but the reference is null in the concrete state, thus we can safely assume that no Fresh object shall be considered"
							+ "\n ** However it seems that the considered references do not to match with this assumtion in this case.");
				}
				return null; // Origin depends on out-of-bound array access: Fresh expansion is neither possible, nor needed
			}
			if (object == null) {
				return null;
			}
			final StringBuilder buf = new StringBuilder();
			String name = object.referenceType().name();
			boolean isArray = false;
			while (name.endsWith("[]")) {
				isArray = true;
				buf.append("[");
				name = name.substring(0, name.length() - 2);
			}
			buf.append(isPrimitiveOrVoidCanonicalName(name) ? toPrimitiveOrVoidInternalName(name) : (isArray ? REFERENCE : "") + internalClassName(name) + (isArray ? TYPEEND : ""));
			return buf.toString();
		}

		@Override
		public boolean isNull(ReferenceSymbolic origin) throws GuidanceException {
			final ObjectReference object = (ObjectReference) this.getValue(origin);
			return (object == null);
		}

		@Override
		public boolean areAlias(ReferenceSymbolic first, ReferenceSymbolic second) throws GuidanceException {
			final ObjectReference objectFirst = (ObjectReference) this.getValue(first);
			final ObjectReference objectSecond = (ObjectReference) this.getValue(second);
			return ((objectFirst == null && objectSecond == null) || 
					(objectFirst != null && objectSecond != null && objectFirst.equals(objectSecond)));
		}

		@Override
		public Object getValue(Symbolic origin) throws GuidanceException {
			this.valueDependsOnSymbolicApply = false;
			final com.sun.jdi.Value val = (com.sun.jdi.Value) getJDIValue(origin);
			if (val instanceof IntegerValue) {
				return this.calc.valInt(((IntegerValue) val).intValue());
			} else if (val instanceof BooleanValue) {
				return this.calc.valBoolean(((BooleanValue) val).booleanValue());
			} else if (val instanceof CharValue) {
				return this.calc.valChar(((CharValue) val).charValue());
			} else if (val instanceof ByteValue) {
				return this.calc.valByte(((ByteValue) val).byteValue());
			} else if (val instanceof DoubleValue) {
				return this.calc.valDouble(((DoubleValue) val).doubleValue());
			} else if (val instanceof FloatValue) {
				return this.calc.valFloat(((FloatValue) val).floatValue());
			} else if (val instanceof LongValue) {
				return this.calc.valLong(((LongValue) val).longValue());
			} else if (val instanceof ShortValue) {
				return this.calc.valShort(((ShortValue) val).shortValue());
			} else if (val instanceof ObjectReference) {
				return val;
			} else { //val instanceof VoidValue || val == null
				return null;
			}
		}

		/**
		 * Returns a JDI object from the concrete state standing 
		 * for a {@link Symbolic}.
		 * 
		 * @param origin a {@link Symbolic}.
		 * @return either a {@link com.sun.jdi.Value}, or a {@link com.sun.jdi.ReferenceType}, or
		 *         a {@link com.sun.jdi.ObjectReference}.
		 * @throws GuidanceException
		 */
		protected Object getJDIValue(Symbolic origin) throws GuidanceException {
			try {
				if (origin instanceof SymbolicLocalVariable) {
					return getJDIValueLocalVariable(((SymbolicLocalVariable) origin).getVariableName());
				} else if (origin instanceof KlassPseudoReference) {
					return getJDIObjectStatic(((KlassPseudoReference) origin).getClassFile().getClassName());
				} else if (origin instanceof SymbolicMemberField) {
					final Object o = getJDIValue(((SymbolicMemberField) origin).getContainer());
					if (!(o instanceof com.sun.jdi.ReferenceType) && !(o instanceof com.sun.jdi.ObjectReference)) {
						throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " : Fails because containing object is " + o);
					}
					return getJDIValueField(((SymbolicMemberField) origin), o);
				} else if (origin instanceof PrimitiveSymbolicMemberArrayLength) {
					final Object o = getJDIValue(((PrimitiveSymbolicMemberArrayLength) origin).getContainer() );
					if (!(o instanceof ArrayReference)) {
						throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " : Fails because containing object is " + o);
					}
					return this.vm.mirrorOf(((ArrayReference) o).length());
				} else if (origin instanceof SymbolicMemberArray) {
					final Object o = getJDIValue(((SymbolicMemberArray) origin).getContainer());
					if (!(o instanceof ArrayReference)) {
						throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " : Fails because containing object is " + o);
					}
					try {
						final Simplex index = (Simplex) eval(((SymbolicMemberArray) origin).getIndex());
						return ((ArrayReference) o).getValue(((Integer) index.getActualValue()).intValue());
					} catch (ClassCastException e) {
						throw new GuidanceException(e);
					} catch (IndexOutOfBoundsException e) {
						throw new IndexOutOfBoundsException(((SymbolicMemberArray) origin).asOriginString());
					}
				} else if (origin instanceof ReferenceSymbolicMemberMapValue) {
					final ReferenceSymbolicMemberMapValue refSymbolicMemberMapValue = (ReferenceSymbolicMemberMapValue) origin;
					final SymbolicApply javaMapContainsKeySymbolicApply;
					try {
						javaMapContainsKeySymbolicApply = (SymbolicApply) calc.applyFunctionPrimitive(BOOLEAN, refSymbolicMemberMapValue.getHistoryPoint(), 
								JAVA_MAP_CONTAINSKEY.toString(), refSymbolicMemberMapValue.getContainer(), refSymbolicMemberMapValue.getKey()).pop();
					} catch (NoSuchElementException | jbse.val.exc.InvalidTypeException | InvalidInputException e) {
						throw new UnexpectedInternalException(e);
					}
					if (!this.symbolicApplyCache.containsKey(javaMapContainsKeySymbolicApply)) {
						throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " : Fails because cointainsKey was not evaluated before evaluating this GET symbol");
					} 
					final SymbolicApplyJVMJDI symbolicApplyVm = this.symbolicApplyCache.get(javaMapContainsKeySymbolicApply); 
					if (!(symbolicApplyVm instanceof InitialMapSymbolicApplyJVMJDI)) {
						throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " : Fails because cointainsKey was evaluated as an ordinary abstractlt-interpreted call, rather than as a JAVA_MAP function");
					} 
					final InitialMapSymbolicApplyJVMJDI initialMapSymbolicApplyVm = (InitialMapSymbolicApplyJVMJDI) symbolicApplyVm;
					final Value val = initialMapSymbolicApplyVm.getValueAtKey();
					if (val != null) {
						this.valueDependsOnSymbolicApply = true;
					}
					return val;
				} else if (origin instanceof PrimitiveSymbolicHashCode) {
					if (	this.valueDependsOnSymbolicApply) {
						throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + 
								" : Fails because the curret implementation of JDI-guidance does not reliably support"
								+ " decisions that deopend on hashCodes of SymbolicApply symbols or their fields");						
					}
					final Object o = getJDIValue(((PrimitiveSymbolicHashCode) origin).getContainer());
					if (!(o instanceof ObjectReference)) {
						throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " : Fails because containing object is " + o);
					}
					final ObjectReference oRef = (ObjectReference) o;
					final Value retVal = oRef.invokeMethod(getCurrentThread(), oRef.referenceType().methodsByName("hashCode").get(0), Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
					return retVal;
				} else if (origin instanceof SymbolicApply) {
					//Implicit invariant: when we see a ReferenceSymbolicApply for the first time, JDI is at the call point of the corresponding function
					final SymbolicApply symbolicApply = (SymbolicApply) origin;
					if (!this.symbolicApplyCache.containsKey(symbolicApply)) {
						final SymbolicApplyJVMJDI symbolicApplyVm = startSymbolicApplyVm(symbolicApply);
						this.symbolicApplyCache.put(symbolicApply, symbolicApplyVm);
					} 
					final SymbolicApplyJVMJDI symbolicApplyVm = this.symbolicApplyCache.get(symbolicApply); 
					this.valueDependsOnSymbolicApply = true;
					return symbolicApplyVm.getRetValue();
				} else {
					throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString());
				}
			} catch (IncompatibleThreadStateException | AbsentInformationException | InvocationException | com.sun.jdi.InvalidTypeException | ClassNotLoadedException e) {
				throw new GuidanceException(e);
			}
		}

		private SymbolicApplyJVMJDI startSymbolicApplyVm(SymbolicApply symbolicApply) throws GuidanceException {
			/* TODO: Add a strategy to limit the maximum number of SymbolicApplyJVMJDI that we might allocate 
			 * to execute uninterpreted functions.
			 * At the moment, we start a new SymbolicApplyJVMJDI for each symbolicApply, and we keep alive all 
			 * SymbolicApplyJVMJDIs that handle any symbolicApply of type ReferenceSymbolicApply, because 
			 * these might be re-queried at future states for the values of fields within the return object. 
			 * However, this can become expensive if there are many invocations of ReferenceSymbolicApply 
			 * uninterpreted functions. 			  
			 */
			if (JAVA_MAP_Utils_isSymbolicApplyOnInitialMap(symbolicApply)) {
				final String op = this.currentHashMapModelMethod; //the operator is containsKey, but we need to move into the jbse.base.JAVA_MAP method where containskey is being evaluated to obtain the proper value of the key
				final List<String> hits = this.symbolicApplyOperatorOccurrences.get(op);
				SymbolicMemberField initialMap = (SymbolicMemberField) symbolicApply.getArgs()[0];
				final InitialMapSymbolicApplyJVMJDI symbolicApplyVm = new InitialMapSymbolicApplyJVMJDI(this.calc, this.runnerParameters, this.stopSignature, this.stopSignatureNumberOfHits, op, hits, initialMap);
				symbolicApplyVm.eval_INVOKEX();
				if (symbolicApplyVm.getValueAtKey() == null) {
					// the return value of containsKey is a boolean and there is no Object associated with this key,
					// thus we do not need this vm any further
					symbolicApplyVm.close(); 
				}
				return symbolicApplyVm;
			}
			final String op = symbolicApply.getOperator();
			final int numberOfHits;
			if (this.symbolicApplyOperatorOccurrences.containsKey(op)) {
				List<String> callCtxs = this.symbolicApplyOperatorOccurrences.get(op);
				callCtxs.add(op); /* TODO: In the future, we should record the full call stack, to allow a less fragile 
						 		   * handling of the breakpoints to reach the invocation of this operations. 
						 		   * Currently, we omit the information on the call context of the operation op,
						 		   * because there is no easy access to the JBSE symbolic state at this point of the code.
						 		   * Thus, we this information allows us just to count the number of times that this operation
						 		   * gets called under JDI, and then reach the n-th (numberOfHits) call point.  
						 		   * This strategy may fail if JDI happens to see additional calls
						 		   * that were not visible to JBSE, e.g., calls done in low-level code such in 
						 		   * the case of dynamic class loading of classes that JBSE assumed as pre-loaded */
				numberOfHits = callCtxs.size();
			} else {
				this.symbolicApplyOperatorOccurrences.put(op, Arrays.asList(op));
				numberOfHits = 1;
			}
			final SymbolicApplyJVMJDI symbolicApplyVm = new SymbolicApplyJVMJDI(this.calc, this.runnerParameters, this.stopSignature, this.stopSignatureNumberOfHits, op, numberOfHits);
			symbolicApplyVm.eval_INVOKEX();
			
			//If the return value is a primitive, we do not need this vm any further
			if (symbolicApply instanceof PrimitiveSymbolicApply) {
				symbolicApplyVm.close(); 
			}

			return symbolicApplyVm;
		}

		private com.sun.jdi.Value getJDIValueLocalVariable(String var) 
		throws GuidanceException, IncompatibleThreadStateException, AbsentInformationException {
			final com.sun.jdi.Value val;
			if ("this".equals(var)) {
				val = rootFrameConcrete().thisObject();
			} else {
				final LocalVariable variable = rootFrameConcrete().visibleVariableByName(var); 
				if (variable == null) {
					throw new GuidanceException(ERROR_BAD_PATH + "{ROOT}:" + var + ".");
				}
				val = rootFrameConcrete().getValue(variable);
			}
			return val;
		}

		private com.sun.jdi.ReferenceType getJDIObjectStatic(String className) 
		throws GuidanceException, IncompatibleThreadStateException, AbsentInformationException {
			final List<ReferenceType> classes = this.vm.classesByName(className);
			if (classes.size() == 1) {
				return classes.get(0);
			} else {
				throw new GuidanceException(ERROR_BAD_PATH + "[" + className + "].");
			}
		}

		private com.sun.jdi.Value getJDIValueField(SymbolicMemberField origin, Object o) 
		throws GuidanceException {
			if (JAVA_MAP_Utils_isInitialMapField(origin)) {
				return JAVA_MAP_Utils_getJDIValueInitalMapField(getCurrentThread(), o);
			}
			final String fieldName = origin.getFieldName();
			if (o instanceof com.sun.jdi.ReferenceType) {
				//the field is static
				final com.sun.jdi.ReferenceType oReferenceType = ((com.sun.jdi.ReferenceType) o);
				final Field fld = oReferenceType.fieldByName(fieldName);
				if (fld == null) {
					throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " (missing field " + fieldName + ").");
				}
				try {
					return oReferenceType.getValue(fld);
				} catch (IllegalArgumentException e) {
					throw new GuidanceException(e);
				}
			} else {
				//the field is not static (note that it can be declared in the superclass)
				final com.sun.jdi.ObjectReference oReference = ((com.sun.jdi.ObjectReference) o);
				final String fieldDeclaringClass = binaryClassName(origin.getFieldClass());
				final List<Field> fields = oReference.referenceType().allFields();
				Field fld = null;
				for (Field _fld : fields) {
					if (_fld.declaringType().name().equals(fieldDeclaringClass) && _fld.name().equals(fieldName)) {
						fld = _fld;
						break;
					}
				}
				if (fld == null) {
					throw new GuidanceException(ERROR_BAD_PATH + origin.asOriginString() + " (missing field " + fieldName + ").");
				}
				try {
					return oReference.getValue(fld);
				} catch (IllegalArgumentException e) {
					throw new GuidanceException(e);
				}
			}
		}
		
		private static boolean JAVA_MAP_Utils_isInitialMapField(Object origin) {
			if (true /* TODO: add a check on the JBSE parameter related to using abstract HashMaps. Is it active?*/ && origin instanceof SymbolicMemberField) {
				SymbolicMemberField originMemberField = (SymbolicMemberField) origin;
				if (binaryClassName(originMemberField.getFieldClass()).equals("java.util.HashMap") && originMemberField.getFieldName().equals("initialMap")) {
					return true;
				}
			}
			return false;
		}

		private static Value JAVA_MAP_Utils_getJDIValueInitalMapField(ThreadReference currentThread, Object o) {
			ObjectReference initialMapRef = (com.sun.jdi.ObjectReference) o;
			try {
				Value intialMapClone = initialMapRef.invokeMethod(currentThread, initialMapRef.referenceType().methodsByName("clone").get(0), Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
				return intialMapClone;
			} catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e) {
				throw new UnexpectedInternalException(e);
			}
		}

		private static boolean JAVA_MAP_Utils_isSymbolicApplyOnInitialMap(SymbolicApply symbolicApply) throws GuidanceException {
			jbse.val.Value[] args = symbolicApply.getArgs();
			if (args.length > 0 && JAVA_MAP_Utils_isInitialMapField(args[0])) {
				if (!symbolicApply.getOperator().equals("java/util/Map:(Ljava/lang/Object;)Z:containsKey")) {
					throw new GuidanceException("Path condition refers to unexpected symbolicApply on a symbolic map: " + symbolicApply.getOperator());
				}
				return true;
			} else {
				return false;
			}
					
		}
		
		public int getCurrentCodeIndex() throws GuidanceException {
			return (int) getCurrentLocation().codeIndex();
		}

		public ThreadReference getCurrentThread() throws GuidanceException {
			if (this.currentExecutionPointEvent != null) {
				if (this.currentExecutionPointEvent instanceof BreakpointEvent) {
					return ((BreakpointEvent) this.currentExecutionPointEvent).thread();
				} else if (this.currentExecutionPointEvent instanceof StepEvent) {
					return ((StepEvent) this.currentExecutionPointEvent).thread();
				} else if (this.currentExecutionPointEvent instanceof MethodExitEvent) {
					return ((MethodExitEvent) this.currentExecutionPointEvent).thread();
				} else {
					throw new GuidanceException("Unexpected JDI failure: current execution point is neither BreakpointEvent nor StepEvent");
				}
			} else {
				throw new GuidanceException("Unexpected JDI failure: current method entry not known ");
			}
		}

		public Location getCurrentLocation() throws GuidanceException {
			if (this.currentExecutionPointEvent != null) {
				if (this.currentExecutionPointEvent instanceof BreakpointEvent) {
					return ((BreakpointEvent) this.currentExecutionPointEvent).location();
				} else if (this.currentExecutionPointEvent instanceof StepEvent) {
					return ((StepEvent) this.currentExecutionPointEvent).location();
				} else if (this.currentExecutionPointEvent instanceof MethodExitEvent) {
					return ((MethodExitEvent) this.currentExecutionPointEvent).location();
				} else {
					throw new GuidanceException("Unexpected JDI failure: current execution point is neither BreakpointEvent nor StepEvent");
				}
			} else {
				throw new GuidanceException("Unexpected JDI failure: current execution point entry not known ");
			}
		}

		@Override
		public void step(State jbseState) throws GuidanceException {
			// Nothing to do: This version of JVMJDI remains stuck at the initial state of the method under analysis
		}

		private static String jdiMethodClassName(Method jdiMeth) {
			return jdiMeth.toString().substring(0, jdiMeth.toString().indexOf(jdiMeth.name() + '(') - 1).replace('.', '/');
		}

		@Override
		public Signature getCurrentMethodSignature() throws ThreadStackEmptyException {
			try {
				final Method jdiMeth = getCurrentLocation().method();
				final String jdiMethClassName = jdiMethodClassName(jdiMeth);
				final String jdiMethDescr = jdiMeth.signature();
				final String jdiMethName = jdiMeth.name();
				return new Signature(jdiMethClassName, jdiMethDescr, jdiMethName);
			} catch (GuidanceException e) {
				throw new UnexpectedInternalException(e);
			}
		}

		@Override
		public int getCurrentProgramCounter() throws ThreadStackEmptyException {
			try {
				return getCurrentCodeIndex();
			} catch (GuidanceException e) {
				throw new UnexpectedInternalException(e);
			}
		}

		@Override
		protected void close() {
			if (this.vm != null) {
				this.vm.exit(0);

				//obviates to inferior process leak
				this.vm.process().destroyForcibly();
				this.vm = null;
			}
			for (SymbolicApplyJVMJDI symbolicApplyVm: symbolicApplyCache.values()) {
				symbolicApplyVm.close();
			}
		}
	}
	
	private static class SymbolicApplyJVMJDI extends JVMJDI {
		protected Value symbolicApplyRetValue;
		private final String symbolicApplyOperator;
		private final int symbolicApplyNumberOfHits;
		private final BreakpointRequest targetMethodExitedBreakpoint;

		public SymbolicApplyJVMJDI(Calculator calc, RunnerParameters runnerParameters, Signature stopSignature, int numberOfHits, String symbolicApplyOperator, int symbolicApplyNumberOfHits) 
		throws GuidanceException {
			super(calc, runnerParameters, stopSignature, numberOfHits);
			this.symbolicApplyOperator = symbolicApplyOperator;
			this.symbolicApplyNumberOfHits = symbolicApplyNumberOfHits;
			/* We set up a control breakpoint to check if, at any next step, JDI erroneously returns from the method under analysis */
			try { 
				final EventRequestManager mgr = this.vm.eventRequestManager();
				Location callPoint = getCurrentThread().frames().get(1).location(); //the current location in caller frame
				targetMethodExitedBreakpoint = mgr.createBreakpointRequest(callPoint.method().locationOfCodeIndex(callPoint.codeIndex() + Offsets.INVOKESPECIALSTATICVIRTUAL_OFFSET));
			} catch (IncompatibleThreadStateException e) {
				throw new UnexpectedInternalException(e);
			}
		}
		
		public Value getRetValue() {
			return this.symbolicApplyRetValue;
		}

		@Override
		protected boolean handleBreakpointEvents(Event event, int numberOfHits) throws GuidanceException {
			if (event.request().equals(this.targetMethodExitedBreakpoint)) {
				try { //Did we exited from target method? Should not happen 
					if (numFramesFromRootFrameConcrete() < 0) {
						throw new UnexpectedInternalException("Exited from target method, while looking for breakpoint");
					}
				} catch (IncompatibleThreadStateException e) {
					throw new UnexpectedInternalException(e);
				}
			}
			return super.handleBreakpointEvents(event, numberOfHits);
		}
		
		protected void eval_INVOKEX() throws GuidanceException {
			//steps and decides
			stepIntoSymbolicApplyMethod();
			this.symbolicApplyRetValue = stepUpToMethodExit();
		}

		protected void stepIntoSymbolicApplyMethod() throws GuidanceException {
			// Make JDI execute the uninterpreted function that corresponds to the symboliApply
			targetMethodExitedBreakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
			targetMethodExitedBreakpoint.enable();
			goToBreakpoint(signatureOf(symbolicApplyOperator), 0, symbolicApplyNumberOfHits);
			targetMethodExitedBreakpoint.disable();			
		}
		
		private static Signature signatureOf(String unintFuncOperator) {
			final String[] parts = unintFuncOperator.split(":");
			return new Signature(parts[0], parts[1], parts[2]);
		}

		private Value stepUpToMethodExit() throws GuidanceException {
			final int currFrames;
			try {
				currFrames = getCurrentThread().frameCount();
			} catch (IncompatibleThreadStateException e) {
				throw new GuidanceException(e);
			}

			final EventRequestManager mgr = this.vm.eventRequestManager();
			final MethodExitRequest mexr = mgr.createMethodExitRequest();
			mexr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
			mexr.enable();

			this.vm.resume();
			final EventQueue queue = this.vm.eventQueue();

			MethodExitEvent mthdExitEvent = null;
			boolean exitFound = false;
			while (!exitFound) {
				try {
					final EventSet eventSet = queue.remove();
					final EventIterator it = eventSet.eventIterator();
					while (!exitFound && it.hasNext()) {
						final Event event = it.nextEvent();

						if (event instanceof MethodExitEvent) {
							mthdExitEvent = (MethodExitEvent) event;
							if (mthdExitEvent.thread().frameCount() == currFrames) { 
								exitFound = true;
								this.currentExecutionPointEvent = event;
							}
						}
					}
					if (!exitFound) {
						eventSet.resume();
					}
				} catch (InterruptedException e) {
					throw new GuidanceException(e);
					//TODO is it ok?
				} catch (VMDisconnectedException | IncompatibleThreadStateException e) {
					throw new GuidanceException(e);
				}
			}

			mexr.disable();
			return mthdExitEvent.returnValue();
		}
		
	}	
	
	private static class InitialMapSymbolicApplyJVMJDI extends SymbolicApplyJVMJDI {
		public static final String callContextSeparator = "&&";
		private final ObjectReference initialMapRef;
		private final List<String> hits;
		private Value valueAtKey;

		public InitialMapSymbolicApplyJVMJDI(Calculator calc, RunnerParameters runnerParameters, Signature stopSignature, int numberOfHits, String symbolicApplyOperator, List<String> hits, SymbolicMemberField initialMapOrigin) 
		throws GuidanceException {
			super(calc, runnerParameters, stopSignature, numberOfHits, symbolicApplyOperator, hits.size());
			initialMapRef = (ObjectReference) getJDIValue(initialMapOrigin);
			this.hits = hits;
		}
		
		@Override
		protected void eval_INVOKEX() throws GuidanceException {
			stepIntoSymbolicApplyMethod();
			try {
				final ObjectReference keyRef = (ObjectReference) getCurrentThread().frame(0).getArgumentValues().get(0);
				this.symbolicApplyRetValue = initialMapRef.invokeMethod(getCurrentThread(), initialMapRef.referenceType().methodsByName("containsKey").get(0), Collections.singletonList(keyRef), ObjectReference.INVOKE_SINGLE_THREADED);
				this.valueAtKey = initialMapRef.invokeMethod(getCurrentThread(), initialMapRef.referenceType().methodsByName("get").get(0), Collections.singletonList(keyRef), ObjectReference.INVOKE_SINGLE_THREADED);
			} catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException | InvocationException e) {
				throw new GuidanceException("Failed to call method on the concrete HashMap that corresponds to a symbolic HashMa:" + e);
			}
		}
		
		public Value getValueAtKey() {
			return this.valueAtKey;
		}

		@Override
		protected boolean handleBreakpointEvents(Event event, int numberOfHits) throws GuidanceException {
			boolean atBreakpoint = super.handleBreakpointEvents(event, numberOfHits);
			if (atBreakpoint && hits != null) {
				// We check if the breakpoint is at a method with the right call context
				String[] expectedCallCtx = hits.get(this.hitCounter - 1).split(callContextSeparator);
				try {
					int numFrames = this.numFramesFromRootFrameConcrete() + 1;
					boolean callStackLenOk = expectedCallCtx.length == numFrames; //check if the call stack corresponds, otherwise roll-back the decision
					if (!callStackLenOk) {
						--this.hitCounter;
						return false;
					}
					boolean callStackOk = true;
					for (int i = 0; i < this.numFramesFromRootFrameConcrete(); ++i) {
						String method = this.getCurrentThread().frame(i).location().method().name();
						String callCtxItem = expectedCallCtx[expectedCallCtx.length - 1 - i];
						String name = callCtxItem.substring(callCtxItem.lastIndexOf(':') + 1);
						callStackOk = method.equals(name);
						if (!callStackOk) {
							--this.hitCounter;
							return false;
						}					
					}
				} catch (IncompatibleThreadStateException e) {
					throw new GuidanceException("JDI failed to check the call stack at breakpoint on HashMap method related to symbolic HsahMap: " + e);
				}
			}
			return atBreakpoint;
		}

	}

	public void notifyExecutionOfHashMapModelMethod(Signature currentMethodSignature, Signature[] callCtx) {
		List<String> prevHits = ((JVMJDI) this.jvm).symbolicApplyOperatorOccurrences.get(currentMethodSignature.toString());
		if (prevHits == null) {
			prevHits = new ArrayList<>();
			((JVMJDI) this.jvm).symbolicApplyOperatorOccurrences.put(currentMethodSignature.toString(), prevHits);
		}
		String callCtxString = callCtx[0].toString();
		for (int i = 1; i < callCtx.length; ++i) {
			callCtxString += InitialMapSymbolicApplyJVMJDI.callContextSeparator + callCtx[i];
		}
		prevHits.add(callCtxString);
		((JVMJDI) this.jvm).currentHashMapModelMethod = currentMethodSignature.toString();
	}
	
}

