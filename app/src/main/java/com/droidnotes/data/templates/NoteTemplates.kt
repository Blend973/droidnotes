package com.droidnotes.data.templates

data class NoteTemplate(
    val id: String,
    val name: String,
    val category: String,
    val summary: String,
    val defaultTitle: String,
    val content: String,
)

object NoteTemplates {
    val all: List<NoteTemplate> = listOf(
        NoteTemplate(
            id = "blank",
            name = "Blank Note",
            category = "Core",
            summary = "Empty OLED canvas ready for freeform Markdown.",
            defaultTitle = "Untitled Note",
            content = "",
        ),
        NoteTemplate(
            id = "technical_spec",
            name = "Technical Spec / ADR",
            category = "Engineering",
            summary = "Architecture context, diagram block, decision matrix, and rollout tasks.",
            defaultTitle = "ADR: System Architecture",
            content = """
                # ADR: System Architecture
                #architecture #engineering

                > **Status:** Proposed | **Context:** Offline-first local engine

                ## Context
                Describe the technical constraint, latency budget, or design driver. Link related docs via [[System Overview]].

                ## Architecture Flow
                ```diagram
                Client UI -> ViewModel : StateFlow
                ViewModel -> Parser : Dispatchers.Default
                ViewModel -> SQLite : Dispatchers.IO
                ```

                ## Decision Matrix
                | Option | Memory | Startup | Verdict |
                | :--- | :---: | :---: | :--- |
                | Native Engine | Low | < 40ms | Selected |
                | WebView Bridge | High | > 320ms | Rejected |

                ## Complexity Target
                Render latency remains bounded by ${'$'}O(n \log n)${'$'} for ${'$'}n${'$'} blocks, with target frame time ${'$'}\Delta t \leq 16.6\text{ ms}${'$'}.

                ## Action Items
                - [x] Define immutable AST nodes
                - [ ] Benchmark FTS4 query on 5,000 entries
                - [ ] Validate multi-page PDF export
            """.trimIndent(),
        ),
        NoteTemplate(
            id = "research_latex",
            name = "Research & LaTeX Paper",
            category = "Academic",
            summary = "Abstract, stacked equations, empirical chart, and numbered citations.",
            defaultTitle = "Research Note: Spectral Bounds",
            content = """
                # Research Note: Spectral Bounds
                #research #maths

                ## Abstract
                Summary of the hypothesis and empirical observations[^1]. Related groundwork in [[Linear Operators]].

                ## Core Formulation
                For a continuous kernel ${'$'}K(x, y)${'$'} over domain ${'$'}\Omega${'$'}, the energy functional satisfies:

                ${'$'}${'$'}\mathcal{E}(u) = \frac{1}{2} \int_{\Omega} |\nabla u|^2 \, dx + \sum_{i=1}^{n} \lambda_i \sqrt{u_i^2 + \epsilon}${'$'}${'$'}

                Eigenvalue transformation in matrix form:

                ${'$'}${'$'}\begin{pmatrix} \lambda_1 & 0 \\ 0 & \lambda_2 \end{pmatrix} \begin{pmatrix} v_1 \\ v_2 \end{pmatrix} = \lambda v${'$'}${'$'}

                ## Experimental Results
                ```chart
                type: line
                title: Convergence Error vs Iteration
                data:
                Iter 1, 0.84
                Iter 2, 0.51
                Iter 3, 0.29
                Iter 4, 0.14
                Iter 5, 0.06
                ```

                ## Verification Checklist
                - [x] Verify boundary conditions for ${'$'}\Omega${'$'}
                - [ ] Cross-check numerical stability at ${'$'}\epsilon = 10^{-6}${'$'}

                [^1]: Courant & Hilbert, Methods of Mathematical Physics, Vol. I.
            """.trimIndent(),
        ),
        NoteTemplate(
            id = "cornell",
            name = "Cornell Study Notes",
            category = "Study",
            summary = "Cues, structured lecture notes, key formulas, and review checklist.",
            defaultTitle = "Study: Topic Title",
            content = """
                # Study: Topic Title
                #study #notes

                ## Key Questions & Cues
                - What is the primary invariant?
                - How does this connect to [[Core Concepts]]?

                ## Structured Notes
                1. **Core Principle**: Explain the mechanism clearly in your own words.
                2. **Key Equation**: ${'$'}\eta = \frac{W_{\text{out}}}{Q_{\text{in}}} \leq 1 - \frac{T_c}{T_h}${'$'}
                3. **Nuances & Edge Cases**:
                   - Boundary case A
                   - Boundary case B

                ## Summary
                ==Highlight the single takeaway sentence you must remember without looking back.==

                ## Review Tasks
                - [ ] Summarize from memory after 24h
                - [ ] Solve 3 practice problems
            """.trimIndent(),
        ),
        NoteTemplate(
            id = "meeting",
            name = "Meeting Minutes",
            category = "Work",
            summary = "Attendees, agenda, key decisions table, and assigned follow-ups.",
            defaultTitle = "Meeting: Weekly Sync",
            content = """
                # Meeting: Weekly Sync
                #meeting #team

                **Topic:** Sprint Alignment | **Related:** [[Project Roadmap]]

                ## Agenda
                1. Review milestones and blockers
                2. Confirm release scope
                3. Assign owners

                ## Decisions
                | Item | Owner | Target |
                | :--- | :--- | :--- |
                | Finalize parser tests | Core | Friday |
                | Audit export layout | Docs | Monday |

                ## Action Items
                - [ ] Share exported PDF summary
                - [ ] Update [[Project Roadmap]] milestones
            """.trimIndent(),
        ),
        NoteTemplate(
            id = "daily_log",
            name = "Daily Engineering Log",
            category = "Journal",
            summary = "Focused priorities, timestamped log, and reflection checklist.",
            defaultTitle = "Daily Log",
            content = """
                # Daily Log
                #daily #log

                ## Top Priorities
                - [ ] Deep work block: core implementation
                - [ ] Code review & cleanup
                - [ ] Document findings in [[Knowledge Index]]

                ## Notes & Observations
                > Capture concise technical observations, measurements, and ideas here.

                ## Energy & Output Breakdown
                ```chart
                type: bar
                title: Focus Hours by Category
                data:
                Build, 4.5
                Design, 1.5
                Review, 1.0
                Docs, 1.0
                ```
            """.trimIndent(),
        ),
        NoteTemplate(
            id = "data_report",
            name = "Data & Chart Report",
            category = "Analytics",
            summary = "KPI table plus Bar, Pie, and Scatter inline chart blocks.",
            defaultTitle = "Metrics Report",
            content = """
                # Metrics Report
                #analytics #metrics

                ## Summary Metrics
                | Metric | Baseline | Current | Delta |
                | :--- | :---: | :---: | :---: |
                | Cold Start | 420ms | 165ms | -61% |
                | Memory RSS | 94MB | 48MB | -49% |
                | Search Latency | 42ms | 4ms | -90% |

                ## Resource Share
                ```chart
                type: pie
                title: Memory Allocation Breakdown
                data:
                Compose UI, 42
                Room Cache, 24
                AST Cache, 18
                Bitmap Pool, 16
                ```

                ## Throughput vs Payload Size
                ```chart
                type: scatter
                title: Parse Time (ms) vs KB
                data:
                10, 1.2
                25, 2.8
                50, 5.1
                100, 9.8
                200, 19.4
                ```
            """.trimIndent(),
        ),
        NoteTemplate(
            id = "task_board",
            name = "Project Task Board",
            category = "Productivity",
            summary = "Milestone checkboxes grouped by priority with wikilink tracking.",
            defaultTitle = "Project Tasks",
            content = """
                # Project Tasks
                #tasks #planning

                Linked spec: [[ADR: System Architecture]]

                ## Now (High Priority)
                - [ ] Implement core parser edge cases
                - [ ] Verify offline PDF pagination

                ## Next
                - [ ] Add custom study templates
                - [ ] Review tag hierarchy in Graph view

                ## Done
                - [x] Configure OLED monochrome theme
                - [x] Enable debounced auto-save
            """.trimIndent(),
        ),
    )

    val syntaxGuideTemplate = NoteTemplate(
        id = "syntax_guide",
        name = "Markdown, LaTeX & Graph Guide",
        category = "Reference",
        summary = "Interactive showcase of every DroidNotes formatting, math, chart, and citation feature.",
        defaultTitle = "DroidNotes Syntax & Math Guide",
        content = """
            # DroidNotes Syntax & Math Guide
            #guide #markdown #latex

            Welcome to **DroidNotes** — an offline-first, OLED monochrome workspace. You can toggle *Edit*, *Preview*, or *Split* mode anytime, click task checkboxes below directly in Preview, or open the **Graph** tab to inspect `[[wikilinks]]` and `#tags`.

            ## 1. Inline Formatting & Links
            - **Bold**, *Italic*, ***Bold Italic***, ~~Strikethrough~~, ==Highlighted==, and `inline_code()`.
            - External link: [Markdown Specification](https://commonmark.org)
            - Internal wikilink: [[ADR: System Architecture]]
            - Citation reference[^1] and second source[^2].

            ## 2. Interactive Task List
            - [x] Pure `#000000` OLED monochrome theme
            - [x] Zero-animation instant switching
            - [ ] Tap this checkbox in Preview mode to toggle it in source!

            ## 3. LaTeX Equations & Maths
            Inline Gaussian integral ${'$'}\int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}${'$'} and Euler's identity ${'$'}e^{i\pi} + 1 = 0${'$'}.

            Block Quadratic & Matrix expressions:

            ${'$'}${'$'}x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}${'$'}${'$'}

            ${'$'}${'$'}\sum_{k=1}^{n} k^2 = \frac{n(n+1)(2n+1)}{6} \quad \implies \quad \begin{pmatrix} \alpha & \beta \\ -\beta & \alpha \end{pmatrix}${'$'}${'$'}

            ## 4. Tables & Code Blocks
            | Feature | Engine | Storage |
            | :--- | :---: | ---: |
            | Full-Text Search | Room FTS4 | Local SQLite |
            | LaTeX Math | Native Compose | Zero WebView |
            | PDF Export | `PdfDocument` | Multi-page A4 |

            ```kotlin
            fun parseAsync(source: String): MarkdownDocument {
                return MarkdownParser.parse(source)
            }
            ```

            ## 5. Inline Charts & Diagrams
            ```chart
            type: bar
            title: Benchmark Throughput (notes/sec)
            data:
            FTS4 Query, 92
            AST Parse, 78
            HTML Export, 64
            PDF Render, 41
            ```

            ```diagram
            Markdown Source -> AST Parser : Tokens
            AST Parser -> Compose Preview : Blocks
            AST Parser -> Graph Index : Wikilinks & Tags
            ```

            [^1]: Gruber, J. — Markdown Syntax Documentation.
            [^2]: Knuth, D. E. — The TeXbook, Addison-Wesley.
        """.trimIndent(),
    )
}
