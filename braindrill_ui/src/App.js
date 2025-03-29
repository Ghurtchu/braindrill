import React, { useState, useEffect, useRef } from "react";
import "./App.css";

import CodeMirror from "codemirror";
import "codemirror/lib/codemirror.css";
import "codemirror/theme/dracula.css";
import "codemirror/mode/javascript/javascript.js";
import "codemirror/mode/python/python.js";
import "codemirror/mode/ruby/ruby.js";
import "codemirror/mode/perl/perl.js";
import "codemirror/mode/php/php.js";
import "codemirror/mode/clike/clike.js"; // Use clike for Java and similar languages

const App = () => {
  const [language, setLanguage] = useState("python");
  const [output, setOutput] = useState("");

  const editorRef = useRef(null);
  const [editor, setEditor] = useState(null); // Store the editor instance here

  // Initialize CodeMirror
  useEffect(() => {
    const cmInstance = CodeMirror.fromTextArea(editorRef.current, {
      lineNumbers: true,
      mode: language,
      theme: "dracula",
      matchBrackets: true,
      autoCloseBrackets: true,
      lineWrapping: true,
    });
    setEditor(cmInstance); // Store the editor instance

    return () => {
      cmInstance.toTextArea(); // Cleanup editor on unmount
    };
  }, []); // Initialize only once

  // Handle language change
  useEffect(() => {
    if (editor) {
      switch (language) {
        case "python":
          editor.setOption("mode", "python");
          break;
        case "javascript":
          editor.setOption("mode", "javascript");
          break;
        case "java":
          editor.setOption("mode", "text/x-java");
          break;
        case "ruby":
          editor.setOption("mode", "ruby");
          break;
        case "perl":
          editor.setOption("mode", "perl");
          break;
        case "php":
          editor.setOption("mode", "application/x-httpd-php");
          break;
        default:
          editor.setOption("mode", "python");
      }
    }
  }, [language, editor]); // Re-run when language or editor changes

  const executeCode = async () => {
    if (!editor) return; // Make sure editor is available
    const code = editor.getValue(); // Get code from CodeMirror editor
    setOutput(""); // Clear output before executing

    try {
      const response = await fetch(
        `http://localhost:8080/lang/${language.toLowerCase()}`,
        {
          method: "POST",
          headers: { "Content-Type": "text/plain" },
          body: code,
        }
      );

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let result = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        result += decoder.decode(value, { stream: true });
      }

      setOutput(result);
    } catch (error) {
      setOutput("Error executing code.");
    }
  };

  const clearOutput = () => {
    setOutput("");
  };

  return (
    <div className="container">
      <div className="editor">
        <h2>Brain Drill</h2>
        <label htmlFor="language">Select programming language:</label>
        <select
          id="language"
          value={language}
          onChange={(e) => setLanguage(e.target.value)}
        >
          <option value="python">Python</option>
          <option value="javascript">JavaScript</option>
          <option value="java">Java</option>
          <option value="ruby">Ruby</option>
          <option value="perl">Perl</option>
          <option value="php">PHP</option>
        </select>
        <label htmlFor="code"></label>
        <textarea
          ref={editorRef}
          id="code"
          placeholder="Write your code here..."
        />
        <button onClick={executeCode}>Run</button>
      </div>
      <div className="output">
        <h3>Output</h3>
        <div id="output">
          <pre>{output}</pre>
        </div>
        <button onClick={clearOutput}>Clear</button>
      </div>
    </div>
  );
};

export default App;
