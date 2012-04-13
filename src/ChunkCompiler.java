import org.objectweb.asm.*;

public class ChunkCompiler implements Opcodes {
	private final static String[] registerNames = new String[] {
		"a", "b", "c", "x", "y", "z", "i", "j",
		null, null, null, null, null, null, null, null,
		null, null, null, null, null, null, null, null,
		null, null, null, "sp", "pc", "o",
	};
	
	private int _cursor, _start, _end;
	private int[] _program;
	private boolean _trampoline;
	
	private Label _nextInstruction; 
	private Label _delayLabel;

	public byte[] compile(ProcessState p, int index) {
		_start = index;
		_end = endOfChunk(p, index);
		_program = p.ram;
		return getBytes();
	}
	
	private int endOfChunk(ProcessState p, int start) {
		int cursor = start;
		boolean skip = false;

		do {
			int o = p.ram[cursor++];
			int i = o & 0xF;
			int a = (o >> 4) & 0x3F;
			int b = (o >> 10) & 0x3F;

			if (a == 0x1E || a == 0x1F || (a >= 0x10 && a <= 0x17))
				cursor++;
			if (b == 0x1E || b == 0x1F || (b >= 0x10 && b <= 0x17))
				cursor++;
			
			if (!skip && modifiesPC(i, a))
				break ;
			
			skip = bounceBranch(i);
		} while(start < cursor);
		return cursor;
	}
	
	private byte[] getBytes() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(V1_6, ACC_PUBLIC | ACC_SUPER,
				"fragment",
				null,
				"java/lang/Object",
				null);

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
				"chunk", "(LProcessState;)V", null, null
				);
		mv.visitCode();

		_cursor = _start;
		do {
			int o = getNextWord();
			int i = o & 0xF;
			int a = (o >> 4) & 0x3F;
			int b = (o >> 10) & 0x3F;
			
			_delayLabel = _nextInstruction;
			_nextInstruction = new Label();

			// If there is a modification of PC (not immediate), than break
			if( (i == 0) ? 
				emitSpecial(mv, a, b) : 
				emitBasic(mv, i, a, b) ) {
				break ;
			}

			if (_delayLabel != null) {
				mv.visitLabel(_delayLabel);
			}
		} while(_cursor > _start);
		
		mv.visitMaxs(8,4);
		mv.visitEnd();

		return cw.toByteArray();
	}

	private boolean emitBasic(MethodVisitor mv, int o, int a, int b) {		
		// We need this on the stack to write back overflow value
		if (writeOverflow(o)) {
			mv.visitVarInsn(ALOAD, 0);
		}
		
		boolean bounced = _trampoline;
		_trampoline = bounceBranch(o);
		
		getImmediate(mv, a, readA(o), writeback(o));
		getImmediate(mv, b, true, false);
		
		doOperation(mv, o, a);
		
		if (modifiesPC(o, a)) {
			mv.visitInsn(RETURN);
			
			return !bounced;
		}

		return false;
	}

	private boolean emitSpecial(MethodVisitor mv, int o, int a) {
		if (o == 1) {
			emitBasic(mv, 0x1, 0x1C, a);	// Set PC = imm
			emitBasic(mv, 0x1, 0x1A, 0x1C);	// Push PC to stack
			return true;
		} else {
			// WARNING: CANNOT COMPILE
			return true;
		}
	}

	private int getNextWord() {
		return _program[_cursor++];
	}
	
	private boolean readRegister(int o) {
		switch(o) {
		case 0x00: case 0x01: case 0x02: case 0x03:
		case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x1b: case 0x1c: case 0x1d:
			return true;
		default:
			return false;
		}
	}

	private boolean writeback(int o) {
		switch(o){
		case 0x0C: case 0x0D: case 0x0E: case 0x0F:
			return false;
		default:
			return true;
		}
	}
	
	private boolean bounceBranch(int o) {		
		switch(o){
		case 0x0C: case 0x0D: case 0x0E: case 0x0F:
			return true;
		default:
			return false;
		}
	}
	
	private boolean readA(int o) {
		return o != 0x01;
	}
	
	private boolean modifiesPC(int o, int a) {
		return (a == 0x1C) && writeback(o);
	}
	
	private boolean writeOverflow(int o) {
		switch(o) {
		case 0x02: case 0x03: case 0x04: case 0x05:
		case 0x07: case 0x08:
			return true;
		}
		return false;
	}
	
	private void truncate(MethodVisitor mv) {
		mv.visitLdcInsn(0xFFFF);
		mv.visitInsn(IAND);		
	}
	
	private void getImmediate(MethodVisitor mv, int o, boolean read, boolean write) {
		// --- Literal constants
		if (o >= 0x20) {
			mv.visitLdcInsn(o & 0x1F);
			return ;
		}
		
		// --- Register addressing
		if(readRegister(o)) {
			if (write) {
				mv.visitVarInsn(ALOAD, 0);
			}
			if (read) {
				// Reading from PC will return mixed results
				if(o == 0x1C) {
					mv.visitLdcInsn(_cursor);
				} else {
					mv.visitVarInsn(ALOAD, 0);
					mv.visitFieldInsn(GETFIELD, "ProcessState", registerNames[o], "I");
				}
			}
			return ;
		}
		
		// --- Immediate data
		if (o == 0x1F) {
			if (write) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitLdcInsn(_cursor);				
				mv.visitVarInsn(ISTORE, 2);
			}
			int data = getNextWord();
			if (read) {
				mv.visitLdcInsn(data);
			}
			return ;
		}
		
		// --- Memory addressing
		if (write) {
			mv.visitVarInsn(ALOAD, 0);
		}
		if (read) {
			mv.visitVarInsn(ALOAD, 0);
		}

		// --- Determine effective address for this operation
		switch(o) {
		// Register indexed
		case 0x08: case 0x09: case 0x0A: case 0x0B:
		case 0x0C: case 0x0D: case 0x0E: case 0x0F:
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, "ProcessState", registerNames[o&7], "I");
			break ;
		// Register indexed + offset
		case 0x10: case 0x11: case 0x12: case 0x13:
		case 0x14: case 0x15: case 0x16: case 0x17:
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, "ProcessState", registerNames[o&7], "I");
			mv.visitLdcInsn(getNextWord());
			mv.visitInsn(IADD);
			truncate(mv);
			break ;
		case 0x18: // SP++
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, "ProcessState", "sp", "I");
			
			mv.visitInsn(DUP);
			mv.visitInsn(ICONST_1);
			mv.visitInsn(IADD);
			truncate(mv);

			mv.visitVarInsn(ALOAD, 0);
			mv.visitInsn(SWAP);
			mv.visitFieldInsn(PUTFIELD, "ProcessState", "sp", "I");
			break ;			
		case 0x19: // SP
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, "ProcessState", "sp", "I");
			break ;
		case 0x1A: // --SP
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, "ProcessState", "sp", "I");

			mv.visitInsn(ICONST_1);
			mv.visitInsn(ISUB);
			truncate(mv);
			mv.visitInsn(DUP);
			
			mv.visitVarInsn(ALOAD, 0);
			mv.visitInsn(SWAP);
			mv.visitFieldInsn(PUTFIELD, "ProcessState", "sp", "I");
			break ;
		case 0x1E: // [PC++]
			mv.visitLdcInsn(getNextWord());
			break ;
		}

		if (write) {
			if (read) {
				mv.visitInsn(DUP);
			}
			mv.visitVarInsn(ISTORE, 2);
		}
		
		// --- Read back the value (not used in SET operations)
		if (read) {
			mv.visitMethodInsn(INVOKEVIRTUAL, "ProcessState", "read", "(I)I");
		}
	}
	
	private void writeRangeSafe(MethodVisitor mv, int a) {
		Label safe = new Label();
		switch(a) {
		// -- This mode is a special break condition (it's always before current PC)
		case 0x1F:
			break ;
		// --- Memory addressing
		case 0x08: case 0x09: case 0x0A: case 0x0B:
		case 0x0C: case 0x0D: case 0x0E: case 0x0F:
		case 0x10: case 0x11: case 0x12: case 0x13:
		case 0x14: case 0x15: case 0x16: case 0x17:
		case 0x18: case 0x19: case 0x1A: case 0x1E:
			mv.visitVarInsn(ILOAD, 2);
			mv.visitLdcInsn(_cursor);
			mv.visitJumpInsn(IF_ICMPLT, safe);
			mv.visitVarInsn(ILOAD, 2);
			mv.visitLdcInsn(_end);
			mv.visitJumpInsn(IF_ICMPGE, safe);
			mv.visitVarInsn(ALOAD, 0);
			mv.visitLdcInsn(_cursor);
			mv.visitFieldInsn(PUTFIELD, "ProcessState", "pc", "I");
			mv.visitInsn(RETURN);
			mv.visitLabel(safe);
			break ;
		default:
			// We don't write back to literals
			break ;
		}
	}
	
	private void writeResult(MethodVisitor mv, int o) {
		switch(o) {
		// --- Register addressing
		case 0x00: case 0x01: case 0x02: case 0x03:
		case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x1B: case 0x1C: case 0x1D:
			mv.visitFieldInsn(PUTFIELD, "ProcessState", registerNames[o], "I");
			break ;
		// --- Memory addressing
		case 0x08: case 0x09: case 0x0A: case 0x0B:
		case 0x0C: case 0x0D: case 0x0E: case 0x0F:
		case 0x10: case 0x11: case 0x12: case 0x13:
		case 0x14: case 0x15: case 0x16: case 0x17:
		case 0x18: case 0x19: case 0x1A: case 0x1E:
		case 0x1F:
			mv.visitVarInsn(ILOAD, 2);
			mv.visitMethodInsn(INVOKEVIRTUAL, "ProcessState", "write", "(II)V");
			break ;
		default:
			// We don't write back to literals
			break ;
		}
	}
	
	private void doOperation(MethodVisitor mv, int o, int a) {
		switch (o) {
		case 0x01:	// SET: Does not modify data
			writeResult(mv, a);
			writeRangeSafe(mv, a);
			break ;
		case 0x02: // ADD
			opAdd(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x03: // SUB
			opSub(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x04: // MUL
			opMul(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x05: // DIV
			opDiv(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x06: // MOD
			opMod(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x07: // SHL
			opSHL(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x08: // SHR
			opSHR(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x09: // AND
			mv.visitInsn(IAND);
			writeResult(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x0A: // BOR
			mv.visitInsn(IOR);
			writeResult(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x0B: // XOR
			mv.visitInsn(IXOR);
			writeResult(mv, a);
			writeRangeSafe(mv, o);
			break ;
		case 0x0C: // ==
			mv.visitJumpInsn(IF_ICMPNE, _nextInstruction);
			break ;
		case 0x0D: // !=
			mv.visitJumpInsn(IF_ICMPEQ, _nextInstruction);
			break ;
		case 0x0E: // >
			mv.visitJumpInsn(IF_ICMPLE, _nextInstruction);
			break ;
		case 0x0F: // (a&b) != 0
			mv.visitInsn(IAND);
			mv.visitInsn(ICONST_0);
			mv.visitJumpInsn(IF_ICMPEQ, _nextInstruction);
			break ;
		}
	}	

	private void opAdd(MethodVisitor mv, int a) {
		Label _else = new Label();
		Label _done = new Label();
		
		// Add and store results
		mv.visitInsn(IADD);
		mv.visitInsn(DUP);
		mv.visitVarInsn(ISTORE, 1);
		truncate(mv);
		writeResult(mv, a);

		// Check if result has carry
		mv.visitVarInsn(ILOAD,  1);
		mv.visitLdcInsn(16);
		mv.visitInsn(ISHR);
		mv.visitInsn(ICONST_0);
		mv.visitJumpInsn(IF_ICMPEQ,_else);
		
		// Carry = 0xFFFF
		mv.visitInsn(ICONST_1);
		mv.visitJumpInsn(GOTO, _done);
		mv.visitLabel(_else);
		
		// Carry = 0
		mv.visitInsn(ICONST_0);
		mv.visitLabel(_done);

		mv.visitFieldInsn(PUTFIELD, "ProcessState", "o", "I");		
	}

	private void opSub(MethodVisitor mv, int a) {
		Label _else = new Label();
		Label _done = new Label();
		
		// Subtract and store results
		mv.visitInsn(ISUB);
		mv.visitInsn(DUP);
		mv.visitVarInsn(ISTORE, 1);
		truncate(mv);
		writeResult(mv, a);

		// Check if result has carry
		mv.visitVarInsn(ILOAD, 1);
		mv.visitInsn(ICONST_0);
		mv.visitJumpInsn(IF_ICMPGE,_else);
		
		// Carry = 0xFFFF
		mv.visitLdcInsn(0xFFFF);
		mv.visitJumpInsn(GOTO, _done);
		mv.visitLabel(_else);
		
		// Carry = 0
		mv.visitInsn(ICONST_0);
		mv.visitLabel(_done);

		mv.visitFieldInsn(PUTFIELD, "ProcessState", "o", "I");		
	}

	private void opMul(MethodVisitor mv, int a) {
		// Multiply
		mv.visitInsn(IMUL);
		mv.visitInsn(DUP);
		mv.visitVarInsn(ISTORE, 1);

		// Write back lower 8 bits
		truncate(mv);
		writeResult(mv, a);

		// Write back upper 8 bits
		mv.visitVarInsn(ILOAD, 1);
		mv.visitLdcInsn(16);
		mv.visitInsn(ISHR);
		truncate(mv);
		mv.visitFieldInsn(PUTFIELD, "ProcessState", "o", "I");		
	}
	
	private void opDiv(MethodVisitor mv, int a) {
		Label _else = new Label();
		Label _done = new Label();

		mv.visitInsn(DUP);
		// Avoid division by zero
		mv.visitInsn(ICONST_0);
		mv.visitJumpInsn(IF_ICMPEQ, _else);

		// stack = (a << 16) / b
		mv.visitInsn(SWAP);
		mv.visitLdcInsn(16);
		mv.visitInsn(ISHL);
		mv.visitInsn(SWAP);
		mv.visitInsn(IDIV);
		
		// preserve for overflow
		mv.visitInsn(DUP);
		mv.visitVarInsn(ISTORE, 1);
		mv.visitLdcInsn(16);
		mv.visitInsn(ISHR);
		truncate(mv);
		writeResult(mv, a);
		mv.visitVarInsn(ILOAD, 1);
		truncate(mv);

		mv.visitJumpInsn(GOTO, _done);
		mv.visitLabel(_else);

		mv.visitInsn(POP);	// A != 0 (discard, as it is unused
		writeResult(mv, a); // B == 0 (write to result)
		mv.visitInsn(ICONST_0);
		
		mv.visitLabel(_done);
		mv.visitFieldInsn(PUTFIELD, "ProcessState", "o", "I");		
	}
	
	private void opMod(MethodVisitor mv, int a) {
		Label _else = new Label();
		Label _done = new Label();

		mv.visitInsn(DUP);
		// Avoid division by zero
		mv.visitInsn(ICONST_0);
		mv.visitJumpInsn(IF_ICMPNE, _else);
	
		// a % 0 == 0
		mv.visitInsn(POP2);	// discard and replace with 0
		mv.visitInsn(ICONST_0);		
		mv.visitJumpInsn(GOTO, _done);

		// A % B
		mv.visitLabel(_else);
		mv.visitInsn(IREM);
		
		mv.visitLabel(_done);
		
		writeResult(mv, a);
	}
	
	private void opSHL(MethodVisitor mv, int a) {
		mv.visitInsn(ISHL);
		mv.visitInsn(DUP);
		mv.visitVarInsn(ISTORE, 1);
		truncate(mv);
		writeResult(mv, a);

		mv.visitVarInsn(ILOAD, 1);
		mv.visitLdcInsn(16);
		mv.visitInsn(ISHR);
		truncate(mv);
		mv.visitFieldInsn(PUTFIELD, "ProcessState", "o", "I");		
	}
	
	private void opSHR(MethodVisitor mv, int a) {
		mv.visitInsn(DUP2);

		mv.visitInsn(SWAP);
		mv.visitLdcInsn(16);
		mv.visitInsn(ISHL);
		mv.visitInsn(SWAP);
		mv.visitInsn(ISHR);		
		mv.visitVarInsn(ISTORE, 1);

		mv.visitInsn(ISHR);
		truncate(mv);
		writeResult(mv, a);
		
		mv.visitVarInsn(ILOAD, 1);
		truncate(mv);
		mv.visitFieldInsn(PUTFIELD, "ProcessState", "o", "I");		
	}	
}
