package com.github.gappylul.genitfx

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlProcessingInstruction
import com.intellij.psi.xml.XmlTag

class GenerateFxmlMembersAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = psiFile is PsiJavaFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return

        val element = psiFile.findElementAt(editor.caretModel.offset) ?: return
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return
        val qualifiedName = psiClass.qualifiedName ?: return

        var targetFxmlFile: XmlFile? = null
        ProjectRootManager.getInstance(project).fileIndex.iterateContent { virtualFile ->
            if (virtualFile.extension == "fxml") {
                val file = PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile
                val rootTag = file?.rootTag
                if (rootTag?.getAttributeValue("fx:controller") == qualifiedName) {
                    targetFxmlFile = file
                    return@iterateContent false
                }
            }
            true
        }

        val fxmlFile = targetFxmlFile ?: return

        val fxmlImports = mutableListOf<String>()
        fxmlFile.accept(object : XmlRecursiveElementVisitor() {
            override fun visitXmlProcessingInstruction(processingInstruction: XmlProcessingInstruction) {
                super.visitXmlProcessingInstruction(processingInstruction)

                val text = processingInstruction.text
                if (text.startsWith("<?import")) {
                    val match = Regex("""import\s+([\w.]+\*?)""").find(text)
                    if (match != null) fxmlImports.add(match.groupValues[1])
                }
            }
        })

        val fieldsToGenerate = mutableListOf<Pair<String, String>>()
        val methodsToGenerate = mutableSetOf<String>()

        fxmlFile.accept(object : XmlRecursiveElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                super.visitXmlTag(tag)

                val fxId = tag.getAttributeValue("fx:id")
                if (fxId != null) {
                    val fqcn = resolveFxmlType(project, tag.name, fxmlImports)
                    fieldsToGenerate.add(Pair(fqcn, fxId))
                }

                val onAction = tag.getAttributeValue("onAction")
                if (onAction != null && onAction.startsWith("#")) {
                    methodsToGenerate.add(onAction.substring(1))
                }
            }
        })

        WriteCommandAction.runWriteCommandAction(project) {
            val factory = JavaPsiFacade.getElementFactory(project)
            val codeStyleManager = JavaCodeStyleManager.getInstance(project)

            for ((fqcn, name) in fieldsToGenerate) {
                if (psiClass.findFieldByName(name, false) == null) {
                    val fieldText = "@javafx.fxml.FXML\nprivate $fqcn $name;"
                    try {
                        val addedField = psiClass.add(factory.createFieldFromText(fieldText, psiClass))
                        codeStyleManager.shortenClassReferences(addedField)
                    } catch (ex: Exception) { }
                }
            }

            for (methodName in methodsToGenerate) {
                if (psiClass.findMethodsByName(methodName, false).isEmpty()) {
                    val methodText = "@javafx.fxml.FXML\npublic void $methodName(javafx.event.ActionEvent event) {\n\n}"
                    try {
                        val addedMethod = psiClass.add(factory.createMethodFromText(methodText, psiClass))
                        codeStyleManager.shortenClassReferences(addedMethod)
                    } catch (ex: Exception) { }
                }
            }
        }
    }

    private fun resolveFxmlType(project: Project, shortName: String, fxmlImports: List<String>): String {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)

        for (imp in fxmlImports) {
            if (imp.endsWith(".$shortName")) return imp
        }

        for (imp in fxmlImports) {
            if (imp.endsWith(".*")) {
                val fqcn = imp.replace("*", shortName)
                if (facade.findClass(fqcn, scope) != null) return fqcn
            }
        }

        val commonPackages = listOf(
            "javafx.scene.control", "javafx.scene.layout", "javafx.scene.shape",
            "javafx.scene.image", "javafx.scene.text", "javafx.scene.web",
            "javafx.scene.chart", "javafx.scene.media"
        )
        for (pkg in commonPackages) {
            val fqcn = "$pkg.$shortName"
            if (facade.findClass(fqcn, scope) != null) return fqcn
        }

        return shortName
    }
}