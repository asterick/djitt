import java.io.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.ClassLoader;

public class Processor extends ClassLoader  {	
	private static ChunkCompiler cpm = new ChunkCompiler();
	private ProcessState state;
	private Method method;
	
	public Processor(ProcessState p, int index) throws NoSuchMethodException,
														ClassNotFoundException
	{
		byte[] b = cpm.compile(p, index);
		state = p;
			
		try {
			FileOutputStream fs = new FileOutputStream("fragment.class");
			fs.write(b);
			fs.close();
		} catch(FileNotFoundException ex) {
			
		} catch(IOException ioe) {
			
		}

		try {
			method = this.defineClass("fragment", b, 0, b.length)
				.getDeclaredMethod("chunk", ProcessState.class);
		} catch(NoSuchMethodException e) {
			throw e;
		}
	}
	
	public void execute() throws InvocationTargetException, IllegalAccessException {
		try {
			method.invoke(this, state);
		} catch(InvocationTargetException e) {
			throw e;
		} catch(IllegalAccessException e) {
			throw e;
		}
	}
}
