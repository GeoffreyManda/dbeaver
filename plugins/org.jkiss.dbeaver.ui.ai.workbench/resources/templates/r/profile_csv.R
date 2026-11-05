#!/usr/bin/env Rscript
# Profile a UTF-8 CSV with base R; source values are not printed or modified.
# Usage: Rscript profile_csv.R input.csv [delimiter]
args <- commandArgs(trailingOnly = TRUE)
if (length(args) < 1L || length(args) > 2L) stop("Provide input.csv and optional delimiter")
separator <- if (length(args) == 2L) args[[2L]] else ","
if (nchar(separator, type = "chars") != 1L) stop("Delimiter must be one character")
row_limit <- 100000L
column_limit <- 256L
distinct_limit <- 10000L
input <- read.table(args[[1L]], header = TRUE, sep = separator, quote = "\"",
                    comment.char = "", colClasses = "character", na.strings = NULL,
                    stringsAsFactors = FALSE, check.names = FALSE, fill = FALSE,
                    fileEncoding = "UTF-8-BOM", nrows = row_limit + 1L)
if (nrow(input) > row_limit) stop("Split input into files of at most 100000 rows")
if (ncol(input) < 1L || ncol(input) > column_limit) stop("Expected 1..256 columns")
if (any(!nzchar(trimws(names(input)))) || anyDuplicated(names(input))) {
    stop("Column names must be nonblank and unique")
}
profiles <- lapply(seq_along(input), function(index) {
    values <- trimws(input[[index]])
    missing <- is.na(values) | !nzchar(values)
    numeric_values <- suppressWarnings(as.numeric(values[!missing]))
    distinct_count <- length(unique(values[!missing]))
    data.frame(column = names(input)[[index]], rows = nrow(input),
               missing = sum(missing), nonmissing = sum(!missing),
               numeric = sum(is.finite(numeric_values)),
               distinct_count = if (distinct_count <= distinct_limit) distinct_count else NA_integer_,
               distinct_complete = distinct_count <= distinct_limit,
               check.names = FALSE)
})
write.csv(do.call(rbind, profiles), stdout(), row.names = FALSE, na = "")
