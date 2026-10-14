// Verifies that every method/field referenced by the shrunk app code exists in the
// classes that will be present at runtime (app + libraries + android.jar). This catches
// binary incompatibilities between library versions (e.g. a library compiled against a
// newer kotlin-stdlib than the one packaged) that the compiler cannot see.
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.zip.ZipFile

class Info(val name: String, val superName: String?, val interfaces: List<String>) {
    val methods = HashSet<String>()
    val fields = HashSet<String>()
}

fun main(args: Array<String>) {
    val program = args[0]
    val libs = args.drop(1)
    val classes = HashMap<String, Info>()
    val refs = ArrayList<Triple<String, String, String>>() // from, kind+owner, member
    fun load(path: String, collectRefs: Boolean) {
        ZipFile(path).use { z ->
            for (e in z.entries()) {
                if (!e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                val bytes = z.getInputStream(e).readBytes()
                val reader = ClassReader(bytes)
                var info: Info? = null
                reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(v: Int, a: Int, name: String, sig: String?, sup: String?, itfs: Array<out String>?) {
                        info = Info(name, sup, itfs?.toList() ?: emptyList())
                        if (!classes.containsKey(name)) classes[name] = info!!
                    }
                    override fun visitField(a: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? {
                        info?.fields?.add("$name:$desc"); return null
                    }
                    override fun visitMethod(a: Int, name: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor? {
                        info?.methods?.add(name + desc)
                        if (!collectRefs) return null
                        val from = info!!.name
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(op: Int, owner: String, n: String, d: String, itf: Boolean) {
                                if (!owner.startsWith("[")) refs += Triple(from, "M|$owner", n + d)
                            }
                            override fun visitFieldInsn(op: Int, owner: String, n: String, d: String) {
                                refs += Triple(from, "F|$owner", "$n:$d")
                            }
                        }
                    }
                }, ClassReader.SKIP_FRAMES)
            }
        }
    }
    load(program, true)
    libs.forEach { load(it, false) }

    fun hasMember(owner: String, member: String, field: Boolean, seen: HashSet<String> = HashSet()): Boolean? {
        if (!seen.add(owner)) return false
        val c = classes[owner] ?: return null // unknown class (reported separately)
        if (if (field) member in c.fields else member in c.methods) return true
        if (!field && member.startsWith("<init>")) return false
        val parents = listOfNotNull(c.superName) + c.interfaces
        for (p in parents) if (hasMember(p, member, field, seen) == true) return true
        // methods inherited from java.lang.Object
        if (!field && member in setOf("hashCode()I", "equals(Ljava/lang/Object;)Z", "toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;", "clone()Ljava/lang/Object;", "finalize()V", "notify()V", "notifyAll()V", "wait()V", "wait(J)V")) return true
        return false
    }

    val missingMembers = sortedSetOf<String>()
    val missingClasses = sortedSetOf<String>()
    for ((from, kindOwner, member) in refs) {
        val (kind, owner) = kindOwner.split('|', limit = 2)
        when (hasMember(owner, member, kind == "F")) {
            null -> missingClasses += owner
            false -> missingMembers += "$owner.$member  (from $from)"
            true -> Unit
        }
    }
    println("classes analysed: ${classes.size}, references: ${refs.size}")
    println("missing classes: ${missingClasses.size}")
    missingClasses.take(60).forEach { println("  C $it") }
    println("missing members: ${missingMembers.size}")
    missingMembers.take(200).forEach { println("  M $it") }
}
