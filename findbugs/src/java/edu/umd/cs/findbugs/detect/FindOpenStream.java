/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003, University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;

import java.util.*;
import org.apache.bcel.Constants;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import edu.umd.cs.daveho.ba.*;
import edu.umd.cs.findbugs.*;

public class FindOpenStream implements Detector {

	/* ----------------------------------------------------------------------
	 * Helper classes
	 * ---------------------------------------------------------------------- */

	/**
	 * A stream created by the method.
	 */
	private static class Stream {
		/** Location in the method where the stream is created. */
		public final Location creationPoint;

		/** The type of the stream. */
		public final String streamClass;

		public Stream(Location creationPoint, String streamClass) {
			this.creationPoint = creationPoint;
			this.streamClass = streamClass;
		}
	}

	/**
	 * A visitor to model the effect of instructions on the status
	 * of the resource.
	 */
	private static class StreamFrameModelingVisitor extends ResourceValueFrameModelingVisitor {
		private StreamResourceTracker resourceTracker;
		private Stream stream;

		public StreamFrameModelingVisitor(ConstantPoolGen cpg, StreamResourceTracker resourceTracker, Stream stream) {
			super(cpg);
			this.resourceTracker = resourceTracker;
			this.stream = stream;
		}

		public void transferInstruction(InstructionHandle handle, BasicBlock basicBlock) {
			final Instruction ins = handle.getInstruction();
			final ConstantPoolGen cpg = getCPG();
			final ResourceValueFrame frame = getFrame();

			// Model use of instance values in frame slots
			ins.accept(this);

			// Is a resource created or closed by this instruction?
			Location creationPoint = stream.creationPoint;
			if (handle == creationPoint.getHandle() && basicBlock == creationPoint.getBasicBlock()) {
				// Resource creation
				frame.setValue(frame.getNumSlots() - 1, ResourceValue.instance());
				frame.setStatus(ResourceValueFrame.OPEN);
			} else if (resourceTracker.isResourceClose(basicBlock, handle, cpg, stream)) {
				// Resource closed
				frame.setStatus(ResourceValueFrame.CLOSED);
			}

		}

		protected boolean instanceEscapes(InvokeInstruction inv, int instanceArgNum) {
			ConstantPoolGen cpg = getCPG();
			String className = inv.getClassName(cpg);

			boolean escapes = (inv.getOpcode() == Constants.INVOKESTATIC || instanceArgNum != 0);
			//if (escapes) System.out.println("Escape at " + inv + " argNum=" + instanceArgNum);
			return escapes;
		}
	}

	/**
	 * Resource tracker which determines where streams are created,
	 * and how they are used within the method.
	 */
	private static class StreamResourceTracker implements ResourceTracker<Stream> {
		private RepositoryLookupFailureCallback lookupFailureCallback;

		public StreamResourceTracker(RepositoryLookupFailureCallback lookupFailureCallback) {
			this.lookupFailureCallback = lookupFailureCallback;
		}

		public Stream isResourceCreation(BasicBlock basicBlock, InstructionHandle handle, ConstantPoolGen cpg) {
			Instruction ins = handle.getInstruction();
			if (!(ins instanceof NEW))
				return null;

			NEW newIns = (NEW) ins;
			Type type = newIns.getType(cpg);
			String sig = type.getSignature();

			if (!sig.startsWith("L") || !sig.endsWith(";"))
				return null;

			// Track any subclass of InputStream or OutputStream
			// (but not ByteArray variants)
			String className = sig.substring(1, sig.length() - 1).replace('/', '.');
			try {
				boolean isStream =
					(Repository.instanceOf(className, "java.io.InputStream") &&
						!Repository.instanceOf(className, "java.io.ByteArrayInputStream")) ||
					(Repository.instanceOf(className, "java.io.OutputStream") &&
						!Repository.instanceOf(className, "java.io.ByteArrayOutputStream"));
					
				return isStream ? new Stream(new Location(handle, basicBlock), className) : null;
			} catch (ClassNotFoundException e) {
				lookupFailureCallback.reportMissingClass(e);
				return null;
			}
		}

		public boolean isResourceClose(BasicBlock basicBlock, InstructionHandle handle, ConstantPoolGen cpg, Stream resource) {
			Instruction ins = handle.getInstruction();

			if (ins instanceof INVOKEVIRTUAL) {
				// Does this instruction close the stream?
				INVOKEVIRTUAL inv = (INVOKEVIRTUAL) ins;

				String className = inv.getClassName(cpg);
				if (className.equals(resource.streamClass)) {
					String methodName = inv.getName(cpg);
					String methodSig = inv.getSignature(cpg);
					if (methodName.equals("close") && methodSig.equals("()V"))
						return true;
				}
			}

			return false;
		}

		public ResourceValueFrameModelingVisitor createVisitor(Stream resource, ConstantPoolGen cpg) {
			return new StreamFrameModelingVisitor(cpg, this, resource);
		}
	}

	/* ----------------------------------------------------------------------
	 * Fields
	 * ---------------------------------------------------------------------- */

	private BugReporter bugReporter;
	private StreamResourceTracker resourceTracker;

	/* ----------------------------------------------------------------------
	 * Implementation
	 * ---------------------------------------------------------------------- */

	public FindOpenStream(BugReporter bugReporter) {
		this.bugReporter = bugReporter;
		this.resourceTracker = new StreamResourceTracker(bugReporter);
	}

	public void visitClassContext(ClassContext classContext) {

		try {

			JavaClass jclass = classContext.getJavaClass();
			Method[] methodList = jclass.getMethods();
			for (int i = 0; i < methodList.length; ++i) {
				Method method = methodList[i];
				if (method.isAbstract() || method.isNative())
					continue;

				BitSet bytecodeSet = classContext.getBytecodeSet(method);
				if (!bytecodeSet.get(Constants.NEW))
					continue; // no streams created in this method

				MethodGen methodGen = classContext.getMethodGen(method);
				CFG cfg = classContext.getCFG(method);

				Iterator<BasicBlock> bbIter = cfg.blockIterator();
				while (bbIter.hasNext()) {
					BasicBlock basicBlock = bbIter.next();

					Iterator<InstructionHandle> insIter = basicBlock.instructionIterator();
					while (insIter.hasNext()) {
						InstructionHandle handle = insIter.next();

						Stream stream = resourceTracker.isResourceCreation(basicBlock, handle, methodGen.getConstantPool());
						if (stream != null) {
							ResourceValueAnalysis<Stream> analysis = new ResourceValueAnalysis<Stream>(methodGen, resourceTracker, stream);
							Dataflow<ResourceValueFrame> dataflow = new Dataflow<ResourceValueFrame>(cfg, analysis);

							dataflow.execute();

							ResourceValueFrame exitFrame = dataflow.getResultFact(cfg.getExit());

							if (exitFrame.getStatus() == ResourceValueFrame.OPEN) {
								bugReporter.reportBug(new BugInstance("OS_OPEN_STREAM", NORMAL_PRIORITY)
									.addClassAndMethod(methodGen)
									.addSourceLine(methodGen, stream.creationPoint.getHandle())
								);
							}
						}
					}
				}
			}

		} catch (DataflowAnalysisException e) {
			throw new AnalysisException("FindOpenStream caught exception: " + e.toString(), e);
		} catch (CFGBuilderException e) {
			throw new AnalysisException(e.getMessage());
		}

	}

	public void report() {
	}

	public static void main(String[] argv) throws Exception {
		if (argv.length != 3) {
			System.err.println("Usage: " + FindOpenStream.class.getName() + " <class file> <method name> <bytecode offset>");
			System.exit(1);
		}

		String classFile = argv[0];
		String methodName = argv[1];
		int offset = Integer.parseInt(argv[2]);

		final RepositoryLookupFailureCallback lookupFailureCallback = new RepositoryLookupFailureCallback() {
			public void reportMissingClass(ClassNotFoundException ex) {
				ex.printStackTrace();
				System.exit(1);
			}
		};

		JavaClass jclass = new ClassParser(classFile).parse();
		ClassGen classGen = new ClassGen(jclass);
		ConstantPoolGen cpg = classGen.getConstantPool();

		Method[] methodList = jclass.getMethods();
		for (int i = 0; i < methodList.length; ++i) {
			Method method = methodList[i];

			if (!method.getName().equals(methodName))
				continue;

			MethodGen methodGen = new MethodGen(method, jclass.getClassName(), cpg);

			CFGBuilder cfgBuilder = CFGBuilderFactory.create(methodGen);
			cfgBuilder.build();
			CFG cfg = cfgBuilder.getCFG();
			cfg.assignEdgeIds(0);

			BasicBlock creationBlock = null;
			InstructionHandle creationInstruction = null;
/*
			InstructionList il = methodGen.getInstructionList();
			for (InstructionHandle handle = il.getStart(); handle != null; handle = handle.getNext()) {
				if (handle.getPosition() == offset) {
					creationInstruction = handle;
					break;
				}
			}
*/
		blockLoop:
			for (Iterator<BasicBlock> ii = cfg.blockIterator(); ii.hasNext(); ) {
				BasicBlock basicBlock = ii.next();
				for (Iterator<InstructionHandle> j = basicBlock.instructionIterator(); j.hasNext(); ) {
					InstructionHandle handle = j.next();
					if (handle.getPosition() == offset) {
						creationBlock = basicBlock;
						creationInstruction = handle;
						break blockLoop;
					}
				}
			}

			if (creationInstruction == null) throw new IllegalArgumentException("No bytecode with offset " + offset);

			final StreamResourceTracker resourceTracker = new StreamResourceTracker(lookupFailureCallback);
			final Stream stream = resourceTracker.isResourceCreation(creationBlock, creationInstruction, cpg);

			if (stream == null)
				throw new IllegalArgumentException("offset " + offset + " is not a resource creation");

			DataflowTestDriver<ResourceValueFrame> driver = new DataflowTestDriver<ResourceValueFrame>() {
				public AbstractDataflowAnalysis<ResourceValueFrame> createAnalysis(MethodGen methodGen, CFG cfg)
					throws DataflowAnalysisException {
					return new ResourceValueAnalysis<Stream>(methodGen, resourceTracker, stream);
				}
			};

			driver.execute(methodGen, cfg);
			break;
		}
	}

}

// vim:ts=4
