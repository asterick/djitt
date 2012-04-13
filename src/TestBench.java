import java.lang.String;
import java.lang.reflect.InvocationTargetException;

public class TestBench {
	public static void main(String[] args) {
		try {
			ProcessState p = new ProcessState();
		
			p.ram = new int[0x10000];
			
			// ADD [1], 1
			p.ram[0] = (1) | (0x1F << 4) | (0x21 << 10);
			p.ram[1] = 1;
					
			// Set B = 0x1F
			p.ram[2] = (1) | (1 << 4) | (0x3F << 10);
			// SET PC = PC
			p.ram[3] = (1) | (0x1C << 4) | (0x1C << 10);
			
			Processor chunk = new Processor(p, 0);
			
			chunk.execute();
			System.out.print(String.format("%1X %2X", p.ram[1], p.pc));

		} catch(ClassNotFoundException e) {
			System.out.print("Could not locate class");
		} catch(InvocationTargetException e) {
			System.out.print("Invocation target problem");
		} catch(IllegalAccessException e) {
			System.out.print("Illegal access problem");
		} catch(NoSuchMethodException e) {
			System.out.print("No Such Method");
		} catch(SecurityException e) {
			System.out.print("Security Violation");
		}
	}
}
