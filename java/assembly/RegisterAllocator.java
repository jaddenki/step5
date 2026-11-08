package assembly;

import java.util.*;
import assembly.instructions.*;
import assembly.instructions.Instruction.Operand;
import compiler.Scope;
import compiler.LocalScope;
import compiler.Scope.SymbolTableEntry;

public class RegisterAllocator {
    static class Reg {
        String name;
        String holds;
        boolean dirty; // stores u dont need
        Reg(String name) { this.name = name; }
    }

    List<Reg> intRegs = new ArrayList<>();
    List<Reg> floatRegs = new ArrayList<>();
    Set<String> usedRegs = new LinkedHashSet<>(); //for save/restore
    LocalScope scope;
    InstructionList out = new InstructionList();

    static final String SCRATCH = "x3";
    Set<Reg> locked = new HashSet<>();

    public RegisterAllocator(int numRegs, LocalScope scope) {
        this.scope = scope;
        for (int i = 4; i<numRegs; i++) {
            if (i == 8) continue; // x8=fp
            intRegs.add(new Reg("x" + i));
        }
        for (int i = 0; i<numRegs; i++) floatRegs.add(new Reg("f" + i));
    }

    // operand kinds
    static boolean isTemp(String s)   { return s.startsWith("$t") || s.startsWith("$f"); }
    static boolean isLocal(String s)  { return s.startsWith("$l"); }
    static boolean isGlobal(String s) { return s.startsWith("$g"); }
    static boolean isVarOp(String s)  { return isLocal(s) || isGlobal(s); }

    boolean isFloat(Instruction i, Operand o) {
        if (i instanceof PushFloat || i instanceof PopFloat) return true;
        if (i instanceof PushInt || i instanceof PopInt) return false;
        switch (i.getOC()) {
            case FADDS: case FSUBS: case FMULS: case FDIVS:
            case FMVS: case FNEGS: case FIMMS: case GETF: case PUTF:
                return true;
            case FEQ: case FLT: case FLE:
                return o != Operand.DEST;
            default:
                return false;
        }
    }

    List<Reg> allRegs() {
        List<Reg> all = new ArrayList<>(intRegs);
        all.addAll(floatRegs);
        return all;
    }

    boolean isFloatReg(Reg r) {
        return r.name.startsWith("f");
    }

    Reg find(String op, boolean f) {
        for (Reg r : f ? floatRegs : intRegs) if (op.equals(r.holds)) return r;
        return null;
    }

    void clearAll() {
        for (Reg r : allRegs()) { r.holds = null; r.dirty = false; }
    }

    // ---------- memory homes ----------

    String globalAddr(String op) {
        return scope.getSymbolTableEntry(op.substring(2)).addressToString();
    }

    // returns offset from fp for locals and spilled temps
    String fpOffset(String op) {
        if (isLocal(op)) return op.substring(2);
        SymbolTableEntry e = scope.getSymbolTableEntry(op);
        if (e == null) throw new Error("temp " + op + " used before being defined");
        return String.valueOf(e.getAddress());
    }

    void emitLoad(String op, String reg, boolean f) {
        if (isGlobal(op)) {
            out.add(new La(SCRATCH, globalAddr(op)));
            out.add(f ? new Flw(reg, SCRATCH, "0") : new Lw(reg, SCRATCH, "0"));
        } else {
            String off = fpOffset(op);
            out.add(f ? new Flw(reg, "fp", off) : new Lw(reg, "fp", off));
        }
    }

    void emitStore(String op, String reg, boolean f) {
        if (isGlobal(op)) {
            out.add(new La(SCRATCH, globalAddr(op)));
            out.add(f ? new Fsw(reg, SCRATCH, "0") : new Sw(reg, SCRATCH, "0"));
        } else {
            String off = fpOffset(op);
            out.add(f ? new Fsw(reg, "fp", off) : new Sw(reg, "fp", off));
        }
    }

    // ---------- allocate / spill / ensure ----------

    Reg take(Reg r, String op) {
        r.holds = op;
        r.dirty = false;
        usedRegs.add(r.name);
        return r;
    }

    void spill(Reg r) {
        boolean f = isFloatReg(r);
        if (r.dirty) {
            if (isTemp(r.holds) && scope.getSymbolTableEntry(r.holds) == null) {
                scope.addSymbol(f ? Scope.Type.FLOAT : Scope.Type.INT, r.holds);
            }
            emitStore(r.holds, r.name, f);
        }
        r.holds = null;
        r.dirty = false;
    }

    // prefer clean (free to drop), then dirty vars, then dirty temps
    Reg chooseVictim(List<Reg> pool) {
        Reg best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Reg r : pool) {
            if (locked.contains(r)) continue;
            int score = !r.dirty ? 0 : (isVarOp(r.holds) ? 1 : 2);
            if (score < bestScore) { best = r; bestScore = score; }
        }
        if (best == null) throw new Error("out of registers");
        return best;
    }

    Reg allocate(String op, boolean f) {
        List<Reg> pool = f ? floatRegs : intRegs;
        for (Reg r : pool) if (r.holds == null) return take(r, op);
        Reg victim = chooseVictim(pool);
        spill(victim);
        return take(victim, op);
    }

    // source operand: must be in a register
    String ensure(String op, boolean f) {
        Reg r = find(op, f);
        if (r == null) {
            r = allocate(op, f);
            emitLoad(op, r.name, f);
        }
        locked.add(r);
        return r.name;
    }

    // dest operand: get a register, no load, mark dirty
    String define(String op, boolean f) {
        Reg r = find(op, f);
        if (r == null) r = allocate(op, f);
        r.dirty = true;
        locked.add(r);
        return r.name;
    }

    // ---------- per-instruction translation ----------

    void rewriteSources(Instruction i) {
        for (Operand o : new Operand[]{Operand.SRC1, Operand.SRC2}) {
            String s = i.getOperand(o);
            if (Instruction.is3AC(s)) i.setOperand(o, ensure(s, isFloat(i, o)));
        }
    }

    void rewriteDest(Instruction i) {
        String d = i.getOperand(Operand.DEST);
        if (Instruction.is3AC(d)) i.setOperand(Operand.DEST, define(d, isFloat(i, Operand.DEST)));
    }

    void translate(Instruction i) {
        locked.clear();

        if (i instanceof Label || i instanceof Blank) { out.add(i); return; }

        if (i instanceof PushInt || i instanceof PushFloat) {
            boolean f = i instanceof PushFloat;
            String s = i.getOperand(Operand.SRC1);
            String r = Instruction.is3AC(s) ? ensure(s, f) : s;
            out.add(f ? new Fsw(r, "sp", "0") : new Sw(r, "sp", "0"));
            out.add(new Addi("sp", "-4", "sp"));
            return;
        }

        if (i instanceof PopInt || i instanceof PopFloat) {
            boolean f = i instanceof PopFloat;
            String s = i.getOperand(Operand.SRC1);
            out.add(new Addi("sp", "4", "sp"));
            String r = Instruction.is3AC(s) ? define(s, f) : s;
            out.add(f ? new Flw(r, "sp", "0") : new Lw(r, "sp", "0"));
            return;
        }

        if (i.getOC() == Instruction.OpCode.PUTS) {
            String s = i.getOperand(Operand.SRC1);
            out.add(new La(SCRATCH, globalAddr(s)));
            out.add(new PutS(SCRATCH));
            return;
        }

        if (i.getOC() == Instruction.OpCode.JR) {
            // callee may read/write globals in memory
            for (Reg r : allRegs()) {
                if (r.holds != null && isGlobal(r.holds)) spill(r);
            }
            out.add(i);
            return;
        }

        rewriteSources(i);
        rewriteDest(i);
        out.add(i);
    }

    // ---------- basic blocks ----------

    boolean endsBlock(Instruction i) {
        return i instanceof InstructionBranch || i instanceof J || i instanceof Ret;
    }

    List<List<Instruction>> splitBlocks(InstructionList code) {
        List<List<Instruction>> blocks = new ArrayList<>();
        List<Instruction> cur = new ArrayList<>();
        for (Instruction i : code) {
            if (i instanceof Label && !cur.isEmpty()) {
                blocks.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(i);
            if (endsBlock(i)) {
                blocks.add(cur);
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) blocks.add(cur);
        return blocks;
    }

    void writeBack() {
        out.add(new Blank("Saving registers at end of BB"));
        for (Reg r : allRegs()) {
            if (r.dirty && isVarOp(r.holds)) {
                emitStore(r.holds, r.name, isFloatReg(r));
                r.dirty = false;
            }
        }
    }

    public InstructionList allocateFunction(InstructionList body) {
        for (List<Instruction> bb : splitBlocks(body)) {
            out.add(new Blank("Start of BB"));
            Instruction last = bb.get(bb.size() - 1);
            boolean hasTerminator = endsBlock(last);
            int n = hasTerminator ? bb.size() - 1 : bb.size();

            for (int k = 0; k < n; k++) translate(bb.get(k));

            if (hasTerminator) {
                locked.clear();
                rewriteSources(last);   // load branch operands before the stores
                writeBack();
                out.add(last);
            } else {
                writeBack();
            }
            out.add(new Blank("End of BB"));
            clearAll();
        }
        return out;
    }
}
